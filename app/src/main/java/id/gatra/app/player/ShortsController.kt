package id.gatra.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import id.gatra.app.data.AppSettings
import id.gatra.app.data.Repo
import id.gatra.app.data.VideoItem
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
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

data class ShortsState(
    val url: String = "",
    val loading: Boolean = false,
    val playing: Boolean = false,
    val error: Boolean = false,
    val firstFrame: Boolean = false
)

/**
 * Pemutar Shorts (video vertikal pendek): memakai pemutar sendiri, terpisah dari pemutar utama.
 * YouTube tidak menyediakan daftar Shorts resmi, jadi daftar disusun dari hasil pencarian
 * dan hanya video berdurasi sampai 61 detik yang diambil.
 */
class ShortsController(
    private val context: Context,
    private val repo: Repo,
    private val http: OkHttpClient,
    private val settings: AppSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dataSourceFactory = DataSource.Factory { YtChunkedDataSource(http) }

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
        .apply { repeatMode = Player.REPEAT_MODE_ONE }

    private val _items = MutableStateFlow<List<VideoItem>>(emptyList())
    val items: StateFlow<List<VideoItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _state = MutableStateFlow(ShortsState())
    val state: StateFlow<ShortsState> = _state.asStateFlow()

    private val queries = listOf(
        "shorts indonesia", "#shorts viral", "shorts lucu", "shorts musik indonesia",
        "shorts game", "shorts kuliner", "shorts olahraga", "shorts hewan lucu", "shorts tutorial"
    )
    private var queryIndex = (0 until queries.size).random()
    private val seen = HashSet<String>()
    private var feedJob: Job? = null
    private var playJob: Job? = null
    private val infoCache = HashMap<String, StreamInfo>()
    private val prefetching = HashSet<String>()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(playing = isPlaying) }
            }

            override fun onRenderedFirstFrame() {
                _state.update { it.copy(firstFrame = true) }
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(loading = false, error = true) }
            }
        })
    }

    // ---------- Daftar ----------

    fun ensureLoaded() {
        if (_items.value.isEmpty() && feedJob?.isActive != true) loadMore()
    }

    fun loadMore() {
        if (feedJob?.isActive == true) return
        _loading.value = true
        _error.value = null
        feedJob = scope.launch {
            try {
                var added = 0
                var tries = 0
                while (added < 8 && tries < 3) {
                    val q = queries[queryIndex % queries.size]
                    queryIndex++
                    tries++
                    val found = try {
                        repo.search(q, false).videos
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    val shorts = found.filter {
                        it.durationSec in 1..61 && !it.live &&
                            it.url !in settings.hiddenVideos.value &&
                            (it.channelUrl ?: it.channel) !in settings.hiddenChannels.value &&
                            seen.add(it.url)
                    }
                    if (shorts.isNotEmpty()) {
                        _items.value = _items.value + shorts.shuffled()
                        added += shorts.size
                    }
                }
                if (added == 0 && _items.value.isEmpty()) {
                    _error.value = "Gagal memuat Shorts. Periksa internet lalu coba lagi."
                }
            } finally {
                _loading.value = false
            }
        }
    }

    fun refresh() {
        playJob?.cancel()
        player.stop()
        player.clearMediaItems()
        _state.value = ShortsState()
        _items.value = emptyList()
        seen.clear()
        loadMore()
    }

    // ---------- Pemutaran ----------

    /** Putar [v]. Bila sudah itu yang diputar, tidak melakukan apa-apa. */
    fun play(v: VideoItem) {
        val cur = _state.value
        if (cur.url == v.url && !cur.error) return
        playJob?.cancel()
        player.stop()
        player.clearMediaItems()
        _state.value = ShortsState(url = v.url, loading = true)
        playJob = scope.launch {
            try {
                val info = infoCache[v.url] ?: withContext(Dispatchers.IO) {
                    StreamInfo.getInfo(ServiceList.YouTube, v.url)
                }.also { remember(v.url, it) }
                val source = buildSource(info) ?: throw IllegalStateException("Tidak ada aliran yang bisa diputar.")
                player.setMediaSource(source)
                player.prepare()
                player.playWhenReady = true
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

    /** Muat data video berikutnya lebih dulu supaya geseran terasa cepat. */
    fun prefetch(v: VideoItem?) {
        if (v == null || infoCache.containsKey(v.url) || !prefetching.add(v.url)) return
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { StreamInfo.getInfo(ServiceList.YouTube, v.url) }
                remember(v.url, info)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                prefetching.remove(v.url)
            }
        }
    }

    fun retry() {
        val url = _state.value.url
        if (url.isBlank()) return
        val v = _items.value.firstOrNull { it.url == url } ?: return
        _state.update { it.copy(error = false) }
        play(v)
    }

    fun resume() {
        val s = _state.value
        if (s.url.isNotBlank() && !s.error && !s.loading) player.play()
    }

    fun pause() = player.pause()

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun remember(url: String, info: StreamInfo) {
        if (infoCache.size > 25) infoCache.clear()
        infoCache[url] = info
    }

    private fun side(v: VideoStream): Int {
        val w = v.width
        val h = v.height
        return if (w > 0 && h > 0) minOf(w, h) else maxOf(w, h)
    }

    /** Pilih aliran sekitar 480p (sisi pendek) supaya cepat mulai; utamakan MP4 (H.264). */
    private fun buildSource(info: StreamInfo): MediaSource? {
        val audio = pickAudio(info)
        val videoOnly = info.videoOnlyStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        val muxed = info.videoStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        val useSeparate = audio != null && videoOnly.isNotEmpty()
        val pool = if (useSeparate) videoOnly else muxed
        if (pool.isEmpty()) return null
        val target = 480
        val candidates = pool.filter { side(it) <= target }.ifEmpty { listOf(pool.minByOrNull { side(it) }!!) }
        val best = candidates.sortedWith(
            compareBy<VideoStream> { if (it.format == MediaFormat.MPEG_4) 0 else 1 }
                .thenByDescending { side(it) }
        ).first()
        val video = progressive(best.content)
        return if (useSeparate) MergingMediaSource(video, progressive(audio!!.content)) else video
    }

    private fun progressive(url: String): MediaSource =
        ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.Builder().setUri(Uri.parse(url)).build())
}
