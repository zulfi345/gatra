package id.gatra.app.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import id.gatra.app.data.Repo
import id.gatra.app.data.bestUrl
import id.gatra.app.data.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/** Satu pilihan kualitas video. [muxed] = video sudah berisi suara (tanpa audio terpisah). */
class QualityOption(
    val height: Int,
    val label: String,
    val video: VideoStream,
    val muxed: Boolean
)

data class PlayerState(
    val active: Boolean = false,
    val loading: Boolean = false,
    val buffering: Boolean = false,
    val isPlaying: Boolean = false,
    val error: String? = null,
    val url: String = "",
    val info: StreamInfo? = null,
    val qualities: List<QualityOption> = emptyList(),
    val selectedHeight: Int = 0,
    val expanded: Boolean = false,
    val isLive: Boolean = false,
    val auto: Boolean = false,
    val smooth: Boolean = false,
    val prebuffering: Boolean = false,
    val aheadSec: Int = 0
)

/**
 * Pusat pemutaran: mengambil data video lewat NewPipeExtractor lalu memutarnya dengan ExoPlayer.
 * Hidup selama proses aplikasi, jadi video tetap jalan walau layar ditutup / pindah aplikasi.
 */
class PlaybackController(
    private val context: Context,
    private val repo: Repo,
    http: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("gatra", Context.MODE_PRIVATE)
    private val dataSourceFactory = DataSource.Factory { YtChunkedDataSource(http) }
    private var job: Job? = null
    private var gateJob: Job? = null
    private var smooth = prefs.getBoolean(KEY_SMOOTH, false)
    private var currentBitrate = 0.0
    private var hasStarted = false

    /** Pengukur kecepatan internet (dipakai juga oleh ExoPlayer, jadi perkiraannya makin akurat selama memutar). */
    private val bandwidth = DefaultBandwidthMeter.getSingletonInstance(context)

    // Penyangga lebih besar: video mengunduh lebih awal saat sinyal bagus, sehingga
    // tetap lancar ketika sinyal turun sebentar. Mulai putar tetap cepat (2 dtk).
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 40_000,
            /* maxBufferMs = */ 100_000,
            /* bufferForPlaybackMs = */ 2_000,
            /* bufferForPlaybackAfterRebufferMs = */ 6_000
        )
        .build()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setBandwidthMeter(bandwidth)
        .setLoadControl(loadControl)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                _state.update {
                    it.copy(
                        loading = false,
                        buffering = false,
                        error = "Video gagal diputar (${error.errorCodeName}). Coba lagi atau pilih kualitas lain."
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
                // Mode Lancar: bila video kehabisan data di tengah jalan, tahan dulu sampai
                // penyangga terisi lagi supaya tidak putus-putus berulang kali.
                if (playbackState == Player.STATE_BUFFERING && smooth && hasStarted &&
                    player.playWhenReady && gateJob?.isActive != true && !_state.value.isLive
                ) {
                    startGate(REBUFFER_TARGET_MS, REBUFFER_MAX_WAIT_MS)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) hasStarted = true
                _state.update { it.copy(isPlaying = isPlaying) }
            }
        })
    }

    // ---------- Perintah dari UI ----------

    fun open(rawUrl: String) {
        val url = rawUrl.trim()
        val cur = _state.value
        if (cur.active && cur.url == url && cur.error == null) {
            _state.update { it.copy(expanded = true) }
            return
        }
        startService()
        load(url, 0L)
    }

    fun retry() {
        val s = _state.value
        if (s.url.isBlank()) return
        val pos = if (s.info != null) player.currentPosition else 0L
        load(s.url, pos)
    }

    fun setQuality(option: QualityOption) {
        val info = _state.value.info ?: return
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady || _state.value.prebuffering
        prefs.edit().putInt(KEY_HEIGHT, option.height).apply()
        prepare(info, option, pos, wasPlaying)
        _state.update { it.copy(selectedHeight = option.height, error = null, auto = false) }
    }

    /** Mode Lancar 720p: unduh dulu beberapa saat (prabuffer) baru diputar, lalu tetap jalan walau internet pelan. */
    fun setSmooth(on: Boolean) {
        val s0 = _state.value
        val info = s0.info ?: return
        if (s0.isLive) return
        smooth = on
        prefs.edit().putBoolean(KEY_SMOOTH, on).apply()
        val options = buildQualities(info)
        val chosen = if (on) pickQuality(options, 720) else pickAuto(options)
        prefs.edit().putInt(KEY_HEIGHT, if (on) 720 else AUTO).apply()
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady || _state.value.prebuffering
        prepare(info, chosen, pos, wasPlaying)
        _state.update {
            it.copy(
                smooth = on, qualities = options, error = null,
                selectedHeight = chosen?.height ?: 0, auto = !on
            )
        }
    }

    /** Mode Otomatis: kualitas disesuaikan dengan kecepatan internet saat ini. */
    fun setAuto() {
        val s = _state.value
        val info = s.info ?: return
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady || _state.value.prebuffering
        prefs.edit().putInt(KEY_HEIGHT, AUTO).apply()
        val chosen = pickAuto(s.qualities) ?: return
        prepare(info, chosen, pos, wasPlaying)
        _state.update { it.copy(selectedHeight = chosen.height, error = null, auto = true) }
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun expand() = _state.update { it.copy(expanded = true) }
    fun minimize() = _state.update { it.copy(expanded = false) }

    /** Tutup pemutar sepenuhnya (tombol X di mini-player). */
    fun close() {
        stopAll()
        try {
            context.stopService(Intent(context, PlaybackService::class.java))
        } catch (_: Exception) {
        }
    }

    fun stopAll() {
        job?.cancel()
        gateJob?.cancel()
        player.stop()
        player.clearMediaItems()
        _state.value = PlayerState()
    }

    // ---------- Internal ----------

    private fun startService() {
        try {
            context.startService(Intent(context, PlaybackService::class.java))
        } catch (_: Exception) {
            // Tidak fatal: video tetap jalan di depan, hanya tanpa layanan latar.
        }
    }

    private fun load(url: String, startMs: Long) {
        job?.cancel()
        gateJob?.cancel()
        player.stop()
        player.clearMediaItems()
        _state.value = PlayerState(active = true, loading = true, url = url, expanded = true, smooth = smooth)
        job = scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { StreamInfo.getInfo(ServiceList.YouTube, url) }
                val live = info.streamType == StreamType.LIVE_STREAM && !info.hlsUrl.isNullOrBlank()
                val options = if (live) emptyList() else buildQualities(info)
                if (!live && options.isEmpty()) {
                    throw IllegalStateException("Tidak ada aliran video yang bisa diputar untuk video ini.")
                }
                val pref = prefs.getInt(KEY_HEIGHT, AUTO)
                val auto = pref == AUTO && !live
                val chosen = if (auto) pickAuto(options) else pickQuality(options, pref)
                prepare(info, chosen, startMs)
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        url = info.url ?: url,
                        info = info,
                        qualities = options,
                        selectedHeight = chosen?.height ?: 0,
                        isLive = live,
                        auto = auto,
                        smooth = smooth && !live
                    )
                }
                launch {
                    try {
                        repo.onWatched(info)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = friendlyError(e)) }
            }
        }
    }

    private fun prepare(info: StreamInfo, option: QualityOption?, startMs: Long, resume: Boolean = true) {
        val meta = MediaMetadata.Builder()
            .setTitle(info.name)
            .setArtist(info.uploaderName)
            .apply { info.thumbnails.bestUrl()?.let { setArtworkUri(Uri.parse(it)) } }
            .build()

        val source: MediaSource = if (option == null) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(
                MediaItem.Builder().setUri(info.hlsUrl).setMediaMetadata(meta).build()
            )
        } else if (option.muxed) {
            progressive(option.video.content, meta)
        } else {
            val audio = pickAudio(info) ?: throw IllegalStateException("Aliran audio tidak ditemukan.")
            MergingMediaSource(
                progressive(option.video.content, meta),
                progressive(audio.content, null)
            )
        }
        gateJob?.cancel()
        hasStarted = false
        player.setMediaSource(source, startMs)
        player.prepare()
        if (resume && smooth && option != null) {
            currentBitrate = cost(option)
            startGate(INITIAL_TARGET_MS, INITIAL_MAX_WAIT_MS)
        } else {
            player.playWhenReady = resume
        }
    }

    /**
     * Tahan pemutaran sampai penyangga cukup. Mulai putar bila salah satu terpenuhi:
     * (1) data di depan sudah [targetMs]; (2) sisa video diperkirakan selesai terunduh
     * sebelum kepala putar menyusul; (3) video sudah penuh; (4) waktu tunggu habis;
     * (5) pengguna menekan putar sendiri.
     */
    private fun startGate(targetMs: Long, maxWaitMs: Long) {
        gateJob?.cancel()
        player.playWhenReady = false
        gateJob = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            _state.update { it.copy(prebuffering = true, aheadSec = 0) }
            try {
                while (true) {
                    delay(500)
                    if (_state.value.error != null) return@launch
                    if (player.playWhenReady) return@launch // pengguna menekan putar
                    val pos = player.currentPosition
                    val buffered = player.bufferedPosition
                    val ahead = (buffered - pos).coerceAtLeast(0L)
                    _state.update { it.copy(aheadSec = (ahead / 1000).toInt()) }
                    val dur = player.duration
                    val known = dur != C.TIME_UNSET && dur > 0
                    val full = known && buffered >= dur - 500
                    val ended = player.playbackState == Player.STATE_ENDED
                    val est = bandwidth.bitrateEstimate
                    val canFinish = known && est > 0 && currentBitrate > 0 && ahead >= 8_000 && run {
                        val needBits = (dur - buffered) / 1000.0 * currentBitrate
                        val downloadSec = needBits / est
                        val playSec = (dur - pos) / 1000.0
                        downloadSec <= playSec * 0.9
                    }
                    val timeout = SystemClock.elapsedRealtime() - t0 >= maxWaitMs
                    if (ahead >= targetMs || full || ended || canFinish || timeout) break
                }
                player.playWhenReady = true
            } finally {
                _state.update { it.copy(prebuffering = false) }
            }
        }
    }

    private fun progressive(url: String, meta: MediaMetadata?): MediaSource {
        val item = MediaItem.Builder().setUri(url).apply { if (meta != null) setMediaMetadata(meta) }.build()
        return ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)
    }

    private fun pickAudio(info: StreamInfo): AudioStream? {
        val all = info.audioStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        if (all.isEmpty()) return null
        // Utamakan trek asli (bukan hasil dubbing) bila extractor mengetahuinya.
        val originals = all.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
        val pool = if (originals.isNotEmpty()) originals else all
        return pool.sortedWith(
            compareBy<AudioStream> { if (it.format == MediaFormat.M4A) 0 else 1 }
                .thenByDescending { it.averageBitrate }
        ).first()
    }

    private fun buildQualities(info: StreamInfo): List<QualityOption> {
        val out = ArrayList<QualityOption>()
        if (pickAudio(info) != null) {
            info.videoOnlyStreams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..1080 }
                .groupBy { it.height }
                .forEach { (h, list) ->
                    // H.264 (MP4) paling kompatibel di semua HP, jadi didahulukan.
                    val best = if (smooth) {
                        // Mode Lancar: pilih berkas terkecil (hindari AV1 yang berat dan 60 fps yang boros).
                        val noAv1 = list.filter { !it.codec.orEmpty().startsWith("av01") }.ifEmpty { list }
                        val lowFps = noAv1.filter { it.fps <= 30 }.ifEmpty { noAv1 }
                        lowFps.minByOrNull { if (it.bitrate > 0) it.bitrate else Int.MAX_VALUE }!!
                    } else list.sortedWith(
                        compareBy<VideoStream> { if (it.format == MediaFormat.MPEG_4) 0 else 1 }
                            .thenByDescending { it.bitrate }
                    ).first()
                    out += QualityOption(h, label(h, best.fps), best, false)
                }
        }
        info.videoStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height > 0 }
            .forEach { v ->
                if (out.none { it.height == v.height }) out += QualityOption(v.height, label(v.height, v.fps), v, true)
            }
        return out.sortedBy { it.height }
    }

    private fun label(height: Int, fps: Int): String = if (fps > 30) "${height}p$fps" else "${height}p"

    private fun pickQuality(options: List<QualityOption>, wanted: Int): QualityOption? {
        if (options.isEmpty()) return null
        return options.filter { it.height <= wanted }.maxByOrNull { it.height }
            ?: options.minByOrNull { it.height }
    }

    /**
     * Pilih kualitas tertinggi (maksimal 720p) yang muat di ~70% kecepatan internet terukur.
     * Bila belum ada perkiraan, mulai dari kualitas hemat (sekitar 360p).
     */
    private fun pickAuto(options: List<QualityOption>): QualityOption? {
        if (options.isEmpty()) return null
        val est = bandwidth.bitrateEstimate
        val budget = if (est > 0) est * 0.7 else 900_000.0
        val capped = options.filter { it.height <= 720 }.ifEmpty { options }
        return capped.filter { cost(it) <= budget }.maxByOrNull { it.height }
            ?: capped.minByOrNull { it.height }
    }

    /** Perkiraan kebutuhan bit/detik sebuah pilihan (video + audio terpisah bila ada). */
    private fun cost(q: QualityOption): Double {
        val byHeight = when {
            q.height <= 144 -> 150_000
            q.height <= 240 -> 300_000
            q.height <= 360 -> 600_000
            q.height <= 480 -> 1_000_000
            q.height <= 720 -> 2_200_000
            else -> 4_000_000
        }
        val video = if (q.video.bitrate > 0) q.video.bitrate else byHeight
        return video + if (q.muxed) 0.0 else 130_000.0
    }

    private companion object {
        const val KEY_HEIGHT = "quality_height"
        const val AUTO = 0
        const val KEY_SMOOTH = "smooth_mode"
        const val INITIAL_TARGET_MS = 45_000L
        const val INITIAL_MAX_WAIT_MS = 150_000L
        const val REBUFFER_TARGET_MS = 25_000L
        const val REBUFFER_MAX_WAIT_MS = 90_000L
    }
}
