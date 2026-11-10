package id.gatra.app.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
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
    val isLive: Boolean = false
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

    val player: ExoPlayer = ExoPlayer.Builder(context)
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
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
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
        val wasPlaying = player.playWhenReady
        prefs.edit().putInt(KEY_HEIGHT, option.height).apply()
        prepare(info, option, pos)
        player.playWhenReady = wasPlaying
        _state.update { it.copy(selectedHeight = option.height, error = null) }
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
        player.stop()
        player.clearMediaItems()
        _state.value = PlayerState(active = true, loading = true, url = url, expanded = true)
        job = scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { StreamInfo.getInfo(ServiceList.YouTube, url) }
                val live = info.streamType == StreamType.LIVE_STREAM && !info.hlsUrl.isNullOrBlank()
                val options = if (live) emptyList() else buildQualities(info)
                if (!live && options.isEmpty()) {
                    throw IllegalStateException("Tidak ada aliran video yang bisa diputar untuk video ini.")
                }
                val chosen = pickQuality(options, prefs.getInt(KEY_HEIGHT, 720))
                prepare(info, chosen, startMs)
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        url = info.url ?: url,
                        info = info,
                        qualities = options,
                        selectedHeight = chosen?.height ?: 0,
                        isLive = live
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

    private fun prepare(info: StreamInfo, option: QualityOption?, startMs: Long) {
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
        player.setMediaSource(source, startMs)
        player.prepare()
        player.playWhenReady = true
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
                    val best = list.sortedWith(
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

    private companion object {
        const val KEY_HEIGHT = "quality_height"
    }
}
