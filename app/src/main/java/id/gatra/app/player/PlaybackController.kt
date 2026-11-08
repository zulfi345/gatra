package id.gatra.app.player

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import id.gatra.app.data.AppSettings
import id.gatra.app.data.DL_DONE
import id.gatra.app.data.DownloadEntity
import id.gatra.app.data.Library
import id.gatra.app.data.Repo
import id.gatra.app.data.VideoItem
import id.gatra.app.data.bestUrl
import id.gatra.app.data.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.security.MessageDigest

/** Satu pilihan kualitas video. [muxed] = video sudah berisi suara (tanpa audio terpisah). */
class QualityOption(
    val height: Int,
    val label: String,
    val video: VideoStream,
    val muxed: Boolean
)

/** Satu pilihan bahasa audio (trek asli atau dubbing YouTube). */
class AudioTrackOption(val id: String, val label: String, val original: Boolean)

/** Satu pilihan teks (subtitle). */
class SubOption(
    val lang: String,
    val label: String,
    val url: String,
    val mime: String
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
    val aheadSec: Int = 0,
    val speed: Float = 1f,
    val subtitles: List<SubOption> = emptyList(),
    val subSelected: Int = -1,
    val audioOnly: Boolean = false,
    val queue: List<VideoItem> = emptyList(),
    val sleepEndMs: Long = 0L,
    val notice: String? = null,
    val offlineTitle: String? = null,
    val hasPrevious: Boolean = false,
    val audioTracks: List<AudioTrackOption> = emptyList(),
    val audioTrackSel: String? = null
)

// ---------- Fungsi bantu tingkat atas (dipakai juga oleh pengunduh) ----------

internal fun pickAudio(info: StreamInfo, trackId: String? = null): AudioStream? {
    val all = info.audioStreams.filter {
        it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
    }
    if (all.isEmpty()) return null
    // Bila pengguna memilih bahasa audio (dubbing), pakai trek itu.
    val forTrack = if (trackId != null) all.filter { callString(it, "getAudioTrackId") == trackId } else emptyList()
    // Selain itu utamakan trek asli (bukan hasil dubbing) bila extractor mengetahuinya.
    val originals = all.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
    val pool = when {
        forTrack.isNotEmpty() -> forTrack
        originals.isNotEmpty() -> originals
        else -> all
    }
    return pool.sortedWith(
        compareBy<AudioStream> { if (it.format == MediaFormat.M4A) 0 else 1 }
            .thenByDescending { it.averageBitrate }
    ).first()
}

/** Baca properti extractor lewat refleksi supaya build tidak gagal bila nama getter berbeda antar versi. */
private fun callString(target: Any, method: String): String = try {
    (target.javaClass.getMethod(method).invoke(target) as? String).orEmpty()
} catch (e: Exception) {
    ""
}

private fun callBool(target: Any, method: String): Boolean = try {
    (target.javaClass.getMethod(method).invoke(target) as? Boolean) ?: false
} catch (e: Exception) {
    false
}

private fun langTagOf(sub: Any): String {
    val tag = callString(sub, "getLanguageTag")
    if (tag.isNotBlank()) return tag
    return try {
        (sub.javaClass.getMethod("getLocale").invoke(sub) as? java.util.Locale)?.toLanguageTag().orEmpty()
    } catch (e: Exception) {
        ""
    }
}

internal fun audioTrackIdOf(a: AudioStream?): String? =
    if (a == null) null else callString(a, "getAudioTrackId").ifBlank { null }

/** Daftar bahasa audio (asli + dubbing). Kosong bila video hanya punya satu trek. */
internal fun buildAudioTracks(info: StreamInfo): List<AudioTrackOption> {
    val all = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
    val groups = all.groupBy { callString(it, "getAudioTrackId") }.filterKeys { it.isNotBlank() }
    if (groups.size < 2) return emptyList()
    return groups.map { (id, list) ->
        val s = list.first()
        val loc = try {
            (s.javaClass.getMethod("getAudioLocale").invoke(s) as? java.util.Locale)
                ?.getDisplayLanguage(java.util.Locale("id")).orEmpty()
        } catch (e: Exception) {
            ""
        }
        val name = callString(s, "getAudioTrackName").ifBlank { loc }.ifBlank { id }
        val typeName = s.audioTrackType?.name
        val tag = when (typeName) {
            null -> ""
            "ORIGINAL" -> "asli"
            "DUBBED" -> "dubbing"
            "DESCRIPTIVE" -> "deskriptif"
            else -> "alternatif"
        }
        AudioTrackOption(id, if (tag.isEmpty()) name else "$name ($tag)", typeName == "ORIGINAL")
    }.sortedBy { if (it.original) 0 else 1 }
}

private fun qualityLabel(height: Int, fps: Int): String = if (fps > 30) "${height}p$fps" else "${height}p"

internal fun buildQualityOptions(info: StreamInfo, smooth: Boolean): List<QualityOption> {
    val out = ArrayList<QualityOption>()
    if (pickAudio(info) != null) {
        info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..1080 }
            .groupBy { it.height }
            .forEach { (h, list) ->
                val best = if (smooth) {
                    // Mode Lancar: pilih berkas terkecil (hindari AV1 yang berat dan 60 fps yang boros).
                    val noAv1 = list.filter { !callString(it, "getCodec").startsWith("av01") }.ifEmpty { list }
                    val lowFps = noAv1.filter { it.fps <= 30 }.ifEmpty { noAv1 }
                    lowFps.minByOrNull { if (it.bitrate > 0) it.bitrate else Int.MAX_VALUE }!!
                } else {
                    // H.264 (MP4) paling kompatibel di semua HP, jadi didahulukan.
                    list.sortedWith(
                        compareBy<VideoStream> { if (it.format == MediaFormat.MPEG_4) 0 else 1 }
                            .thenByDescending { it.bitrate }
                    ).first()
                }
                out += QualityOption(h, qualityLabel(h, best.fps), best, false)
            }
    }
    info.videoStreams
        .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height > 0 }
        .forEach { v ->
            if (out.none { it.height == v.height }) out += QualityOption(v.height, qualityLabel(v.height, v.fps), v, true)
        }
    return out.sortedBy { it.height }
}

internal fun isMeteredNetwork(context: Context): Boolean = try {
    (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
} catch (e: Exception) {
    false
}

/**
 * Pusat pemutaran: mengambil data video lewat NewPipeExtractor lalu memutarnya dengan ExoPlayer.
 * Hidup selama proses aplikasi, jadi video tetap jalan walau layar ditutup / pindah aplikasi.
 */
class PlaybackController(
    private val context: Context,
    private val repo: Repo,
    private val http: OkHttpClient,
    private val library: Library,
    private val settings: AppSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("gatra", Context.MODE_PRIVATE)
    private val dataSourceFactory = DataSource.Factory { YtChunkedDataSource(http) }

    // Cache disk: video yang baru ditonton bisa diputar ulang / di-seek tanpa mengunduh lagi.
    private val cache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024),
            StandaloneDatabaseProvider(context)
        )
    }
    private val cachedFactory: DataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(dataSourceFactory)
            .setCacheKeyFactory { spec -> cacheKey(spec.uri) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private var job: Job? = null
    private var gateJob: Job? = null
    private var noticeJob: Job? = null
    private var sleepJob: Job? = null
    private var smooth = prefs.getBoolean(KEY_SMOOTH, false)
    private var audioOnly = false
    private var currentBitrate = 0.0
    private var hasStarted = false
    private var subOptions: List<SubOption> = emptyList()
    private var audioTrackId: String? = null
    private val history = ArrayList<String>()
    private var segments: List<DoubleArray> = emptyList()

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
                if (playbackState == Player.STATE_ENDED) {
                    onEnded()
                    return
                }
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

        // Pelompat sponsor (SponsorBlock): memeriksa posisi tiap 0,5 detik, hanya bila ada data.
        scope.launch {
            while (true) {
                delay(500)
                val segs = segments
                if (segs.isEmpty() || !player.isPlaying) continue
                val p = player.currentPosition / 1000.0
                val hit = segs.firstOrNull { p >= it[0] && p < it[1] - 0.8 }
                if (hit != null) {
                    player.seekTo((hit[1] * 1000).toLong())
                    notice("Bagian sponsor dilewati")
                }
            }
        }
    }

    // ---------- Perintah dari UI ----------

    fun open(rawUrl: String) {
        val url = rawUrl.trim()
        val cur = _state.value
        if (cur.active && cur.url == url && cur.error == null) {
            _state.update { it.copy(expanded = true) }
            return
        }
        if (cur.active && cur.url.isNotBlank() && cur.url != url) {
            history.add(cur.url)
            if (history.size > 30) history.removeAt(0)
        }
        startService()
        load(url, 0L)
    }

    /** Kembali ke video yang ditonton sebelumnya. */
    fun playPrevious() {
        if (history.isEmpty()) return
        val prev = history.removeAt(history.size - 1)
        startService()
        load(prev, 0L)
    }

    /** Putar [v] sebagai video berikutnya (bila belum ada yang diputar, langsung diputar). */
    fun playNext(v: VideoItem) {
        if (!_state.value.active) {
            open(v.url)
            return
        }
        _state.update { it.copy(queue = listOf(v) + it.queue) }
        notice("Akan diputar berikutnya")
    }

    /** Taruh [v] di akhir antrean (bila belum ada yang diputar, langsung diputar). */
    fun enqueueLast(v: VideoItem) {
        if (!_state.value.active) {
            open(v.url)
            return
        }
        enqueue(v)
    }

    /** Putar daftar: video pertama langsung, sisanya masuk antrean. */
    fun playAll(videos: List<VideoItem>) {
        val first = videos.firstOrNull() ?: return
        _state.update { it.copy(queue = videos.drop(1)) }
        open(first.url)
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
        val options = buildQualityOptions(info, smooth)
        val chosen = if (on) pickQuality(options, 720) else pickAuto(options)
        prefs.edit().putInt(KEY_HEIGHT, if (on) 720 else AUTO).apply()
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady || s0.prebuffering
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
        val wasPlaying = player.playWhenReady || s.prebuffering
        prefs.edit().putInt(KEY_HEIGHT, AUTO).apply()
        val chosen = pickAuto(s.qualities) ?: return
        prepare(info, chosen, pos, wasPlaying)
        _state.update { it.copy(selectedHeight = chosen.height, error = null, auto = true) }
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    /** [index] = urutan di daftar subtitle; -1 = matikan. */
    fun setSubtitle(index: Int) {
        val opts = subOptions
        val ok = index in opts.indices
        applySubtitle(if (ok) opts[index].lang else null)
        _state.update { it.copy(subSelected = if (ok) index else -1) }
    }

    /** Mode audio saja: hanya audio yang diunduh (hemat data), layar boleh mati. */
    fun setAudioOnly(on: Boolean) {
        audioOnly = on
        _state.update { it.copy(audioOnly = on) }
        reprepare()
    }

    /** Pilih bahasa audio (asli atau dubbing YouTube). */
    fun setAudioTrack(id: String) {
        audioTrackId = id
        _state.update { it.copy(audioTrackSel = id) }
        reprepare()
    }

    private fun reprepare() {
        val s = _state.value
        val info = s.info ?: return
        if (s.isLive || s.qualities.isEmpty()) return
        val chosen = s.qualities.firstOrNull { it.height == s.selectedHeight } ?: pickAuto(s.qualities)
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady || s.prebuffering
        prepare(info, chosen, pos, wasPlaying)
    }

    /** Pengatur waktu tidur: jeda otomatis setelah [minutes] menit. 0 = matikan. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _state.update { it.copy(sleepEndMs = 0L) }
            return
        }
        val ms = minutes * 60_000L
        _state.update { it.copy(sleepEndMs = SystemClock.elapsedRealtime() + ms) }
        sleepJob = scope.launch {
            delay(ms)
            gateJob?.cancel()
            player.playWhenReady = false
            _state.update { it.copy(sleepEndMs = 0L, prebuffering = false) }
            notice("Pengatur waktu tidur: video dijeda")
        }
    }

    fun enqueue(v: VideoItem) {
        _state.update { it.copy(queue = it.queue + v) }
        notice("Ditambahkan ke antrean")
    }

    fun removeFromQueue(index: Int) {
        _state.update { s ->
            if (index in s.queue.indices) s.copy(queue = s.queue.filterIndexed { i, _ -> i != index }) else s
        }
    }

    /** Lompat ke video berikutnya (antrean dulu, lalu video terkait). */
    fun skipNext() {
        if (!playNextFromQueue()) playRelated()
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
        sleepJob?.cancel()
        segments = emptyList()
        audioOnly = false
        audioTrackId = null
        history.clear()
        player.stop()
        player.clearMediaItems()
        _state.value = PlayerState()
    }

    /** Hapus cache video. */
    fun clearCache() {
        try {
            for (k in cache.keys.toList()) cache.removeResource(k)
        } catch (_: Exception) {
        }
    }

    fun cacheBytes(): Long = try {
        cache.cacheSpace
    } catch (e: Exception) {
        0L
    }

    // ---------- Internal ----------

    private fun notice(msg: String) {
        _state.update { it.copy(notice = msg) }
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(2500)
            _state.update { it.copy(notice = null) }
        }
    }

    private fun startService() {
        try {
            context.startService(Intent(context, PlaybackService::class.java))
        } catch (_: Exception) {
            // Tidak fatal: video tetap jalan di depan, hanya tanpa layanan latar.
        }
    }

    private fun onEnded() {
        if (_state.value.isLive) return
        if (playNextFromQueue()) return
        if (settings.autoplay) playRelated()
    }

    private fun playNextFromQueue(): Boolean {
        val q = _state.value.queue
        val next = q.firstOrNull() ?: return false
        _state.update { it.copy(queue = q.drop(1)) }
        open(next.url)
        return true
    }

    private fun playRelated() {
        val s = _state.value
        val info = s.info ?: return
        val next = info.relatedItems.filterIsInstance<StreamInfoItem>()
            .firstOrNull { it.url != s.url && it.url !in history && it.streamType != StreamType.LIVE_STREAM } ?: return
        open(next.url)
    }

    private fun applySubtitle(lang: String?) {
        val b = player.trackSelectionParameters.buildUpon()
        if (lang == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            b.setPreferredTextLanguage(lang)
        }
        player.trackSelectionParameters = b.build()
    }

    private fun load(url: String, startMs: Long) {
        job?.cancel()
        gateJob?.cancel()
        player.stop()
        player.clearMediaItems()
        player.setPlaybackSpeed(1f)
        applySubtitle(null)
        segments = emptyList()
        subOptions = emptyList()
        audioTrackId = null
        val prev = _state.value
        _state.value = PlayerState(
            active = true, loading = true, url = url, expanded = true, smooth = smooth,
            queue = prev.queue, sleepEndMs = prev.sleepEndMs, audioOnly = audioOnly,
            hasPrevious = history.isNotEmpty()
        )
        job = scope.launch {
            // Video yang sudah diunduh diputar langsung dari HP (tanpa internet, tanpa kuota).
            val local = try {
                library.downloadFor(url)?.takeIf { it.status == DL_DONE && filesExist(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (local != null) {
                loadLocal(local, startMs)
                return@launch
            }
            try {
                val info = withContext(Dispatchers.IO) { StreamInfo.getInfo(ServiceList.YouTube, url) }
                val live = info.streamType == StreamType.LIVE_STREAM && !info.hlsUrl.isNullOrBlank()
                val options = if (live) emptyList() else buildQualityOptions(info, smooth)
                if (!live && options.isEmpty()) {
                    throw IllegalStateException("Tidak ada aliran video yang bisa diputar untuk video ini.")
                }
                val pref = prefs.getInt(KEY_HEIGHT, AUTO)
                val auto = pref == AUTO && !live
                val chosen = if (auto) pickAuto(options) else pickQuality(options, pref)
                subOptions = if (live) emptyList() else buildSubs(info)
                val tracks = if (live) emptyList() else buildAudioTracks(info)
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
                        smooth = smooth && !live,
                        subtitles = subOptions,
                        audioTracks = tracks,
                        audioTrackSel = if (tracks.isEmpty()) null else audioTrackIdOf(pickAudio(info))
                    )
                }
                loadSegments(info)
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

    private fun filesExist(d: DownloadEntity): Boolean {
        val v = d.videoPath?.let { File(it).exists() } ?: false
        val a = d.audioPath?.let { File(it).exists() } ?: false
        return v || a
    }

    private fun localSource(file: File, meta: MediaMetadata?): MediaSource {
        val item = MediaItem.Builder().setUri(Uri.fromFile(file)).apply { if (meta != null) setMediaMetadata(meta) }.build()
        return ProgressiveMediaSource.Factory(FileDataSource.Factory()).createMediaSource(item)
    }

    private suspend fun loadLocal(d: DownloadEntity, startMs: Long) {
        val meta = MediaMetadata.Builder()
            .setTitle(d.title)
            .setArtist(d.channel)
            .apply { d.thumbnail?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val v = d.videoPath?.let { File(it) }?.takeIf { it.exists() }
        val a = d.audioPath?.let { File(it) }?.takeIf { it.exists() }
        val source: MediaSource = when {
            v != null && a != null -> MergingMediaSource(localSource(v, meta), localSource(a, null))
            v != null -> localSource(v, meta)
            a != null -> localSource(a, meta)
            else -> return
        }
        hasStarted = false
        player.setMediaSource(source, startMs)
        player.prepare()
        player.playWhenReady = true
        _state.update { it.copy(loading = false, error = null, offlineTitle = d.title, selectedHeight = d.quality) }
        // Detail video (deskripsi, video terkait) dimuat bila internet tersedia; gagal pun tidak masalah.
        try {
            val info = withContext(Dispatchers.IO) { StreamInfo.getInfo(ServiceList.YouTube, d.url) }
            _state.update { it.copy(info = info) }
            try {
                repo.onWatched(info)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun prepare(info: StreamInfo, option: QualityOption?, startMs: Long, resume: Boolean = true) {
        val meta = MediaMetadata.Builder()
            .setTitle(info.name)
            .setArtist(info.uploaderName)
            .apply { info.thumbnails.bestUrl()?.let { setArtworkUri(Uri.parse(it)) } }
            .build()

        val audioStream = pickAudio(info, audioTrackId)
        val audioBranch = audioOnly && option != null && audioStream != null

        val base: MediaSource = when {
            option == null -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(
                MediaItem.Builder().setUri(info.hlsUrl).setMediaMetadata(meta).build()
            )
            audioBranch -> progressive(audioStream!!.content, meta)
            option.muxed -> progressive(option.video.content, meta)
            else -> {
                val audio = audioStream ?: throw IllegalStateException("Aliran audio tidak ditemukan.")
                MergingMediaSource(
                    progressive(option.video.content, meta),
                    progressive(audio.content, null)
                )
            }
        }
        val subs = if (option != null && !audioBranch) subtitleSources() else emptyList()
        val source: MediaSource = if (subs.isEmpty()) base else MergingMediaSource(base, *subs.toTypedArray())

        gateJob?.cancel()
        hasStarted = false
        player.setMediaSource(source, startMs)
        player.prepare()
        if (resume && smooth && option != null) {
            currentBitrate = if (audioBranch) 130_000.0 else cost(option)
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
        return ProgressiveMediaSource.Factory(cachedFactory).createMediaSource(item)
    }

    private fun cacheKey(uri: Uri): String {
        val id = uri.getQueryParameter("id")
        val itag = uri.getQueryParameter("itag")
        val clen = uri.getQueryParameter("clen")
        val xtags = uri.getQueryParameter("xtags")
        return if (id != null && itag != null) "yt_${id}_${itag}_${clen.orEmpty()}_${xtags.orEmpty()}" else uri.toString()
    }

    // ---------- Subtitle ----------

    private fun buildSubs(info: StreamInfo): List<SubOption> {
        val all = info.subtitles.filter { it.isUrl }
        if (all.isEmpty()) return emptyList()
        val best = all.groupBy { langTagOf(it) }
            .filterKeys { it.isNotBlank() }
            .map { (_, list) ->
                list.sortedWith(
                    compareBy<org.schabi.newpipe.extractor.stream.SubtitlesStream>(
                        { callBool(it, "isAutoGenerated") },
                        { if (it.format?.name == "VTT") 0 else 1 }
                    )
                ).first()
            }
            .sortedWith(
                compareBy<org.schabi.newpipe.extractor.stream.SubtitlesStream>(
                    { langTagOf(it) != "id" },
                    { langTagOf(it) != "en" },
                    { callString(it, "getDisplayLanguageName") }
                )
            )
            .take(15)
        return best.map {
            val tag = langTagOf(it)
            val label = callString(it, "getDisplayLanguageName").ifBlank { tag } +
                if (callBool(it, "isAutoGenerated")) " (otomatis)" else ""
            SubOption(tag, label, it.content, it.format?.let { f -> callString(f, "getMimeType") }.orEmpty().ifBlank { "text/vtt" })
        }
    }

    private fun subtitleSources(): List<MediaSource> = subOptions.map { o ->
        val cfg = MediaItem.SubtitleConfiguration.Builder(Uri.parse(o.url))
            .setMimeType(o.mime)
            .setLanguage(o.lang)
            .setLabel(o.label)
            .setSelectionFlags(0)
            .build()
        SingleSampleMediaSource.Factory(dataSourceFactory)
            .setTreatLoadErrorsAsEndOfStream(true)
            .createMediaSource(cfg, C.TIME_UNSET)
    }

    // ---------- SponsorBlock ----------

    private fun loadSegments(info: StreamInfo) {
        segments = emptyList()
        if (!settings.sponsorBlock || info.streamType == StreamType.LIVE_STREAM) return
        val id = info.id ?: return
        scope.launch {
            val found = withContext(Dispatchers.IO) { fetchSegments(id) }
            segments = found
        }
    }

    private fun fetchSegments(id: String): List<DoubleArray> {
        return try {
            // Hanya 4 karakter awal hash ID yang dikirim, demi privasi (cara resmi SponsorBlock).
            val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(4)
            val url = "https://sponsor.ajay.app/api/skipSegments/$hash".toHttpUrl().newBuilder()
                .addQueryParameter("categories", "[\"sponsor\",\"selfpromo\",\"interaction\"]")
                .build()
            val req = Request.Builder().url(url).header("User-Agent", "Gatra").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                val body = r.body?.string().orEmpty()
                val out = ArrayList<DoubleArray>()
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("videoID") != id) continue
                    val segs = obj.optJSONArray("segments") ?: continue
                    for (j in 0 until segs.length()) {
                        val seg = segs.getJSONObject(j)
                        if (seg.optString("actionType", "skip") != "skip") continue
                        val range = seg.optJSONArray("segment") ?: continue
                        val s = range.optDouble(0)
                        val e = range.optDouble(1)
                        if (e - s >= 1.0) out += doubleArrayOf(s, e)
                    }
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- Kualitas ----------

    /**
     * Pilih kualitas tertinggi (maksimal 720p, atau 360p bila Hemat Data aktif di jaringan seluler)
     * yang muat di ~70% kecepatan internet terukur. Bila belum ada perkiraan, mulai dari kualitas hemat.
     */
    private fun pickAuto(options: List<QualityOption>): QualityOption? {
        if (options.isEmpty()) return null
        val est = bandwidth.bitrateEstimate
        val budget = if (est > 0) est * 0.7 else 900_000.0
        val cap = if (settings.dataSaver && isMeteredNetwork(context)) 360 else 720
        val capped = options.filter { it.height <= cap }.ifEmpty { options.take(1) }
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

    private fun pickQuality(options: List<QualityOption>, wanted: Int): QualityOption? {
        if (options.isEmpty()) return null
        return options.filter { it.height <= wanted }.maxByOrNull { it.height }
            ?: options.minByOrNull { it.height }
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
