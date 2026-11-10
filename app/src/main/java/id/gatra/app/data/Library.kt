package id.gatra.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.schabi.newpipe.extractor.stream.StreamInfo

/** Pengaturan aplikasi (disimpan di SharedPreferences "gatra"). */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("gatra", Context.MODE_PRIVATE)

    /** Putar video berikutnya otomatis setelah selesai. */
    var autoplay: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(v) = prefs.edit().putBoolean("autoplay", v).apply()

    /** Lewati bagian sponsor memakai layanan komunitas SponsorBlock (mengirim ID video ke sponsor.ajay.app). */
    var sponsorBlock: Boolean
        get() = prefs.getBoolean("sponsorblock", false)
        set(v) = prefs.edit().putBoolean("sponsorblock", v).apply()

    /** Batasi kualitas otomatis maksimal 360p saat memakai data seluler. */
    var dataSaver: Boolean
        get() = prefs.getBoolean("data_saver", false)
        set(v) = prefs.edit().putBoolean("data_saver", v).apply()

    private val _hiddenVideos = MutableStateFlow<Set<String>>(HashSet(prefs.getStringSet("hidden_videos", emptySet()) ?: emptySet()))
    private val _hiddenChannels = MutableStateFlow<Set<String>>(HashSet(prefs.getStringSet("hidden_channels", emptySet()) ?: emptySet()))

    /** Video yang dipilih "Tidak tertarik" (disembunyikan dari rekomendasi di Beranda). */
    val hiddenVideos: StateFlow<Set<String>> = _hiddenVideos.asStateFlow()

    /** Channel yang dipilih "Jangan rekomendasikan channel" (alamat channel, atau namanya bila alamat tak ada). */
    val hiddenChannels: StateFlow<Set<String>> = _hiddenChannels.asStateFlow()

    fun hideVideo(url: String) {
        val n = _hiddenVideos.value + url
        prefs.edit().putStringSet("hidden_videos", HashSet(n)).apply()
        _hiddenVideos.value = n
    }

    fun hideChannel(key: String) {
        val n = _hiddenChannels.value + key
        prefs.edit().putStringSet("hidden_channels", HashSet(n)).apply()
        _hiddenChannels.value = n
    }

    fun clearHidden() {
        prefs.edit().remove("hidden_videos").remove("hidden_channels").apply()
        _hiddenVideos.value = emptySet()
        _hiddenChannels.value = emptySet()
    }

    /**
     * Bahasa audio utama (kode bahasa, misalnya "id" = Indonesia). Dipakai otomatis di setiap video yang punya
     * trek audio bahasa itu (dubbing YouTube); bila tidak ada, dipakai audio asli. Kosong = selalu audio asli.
     * Berubah otomatis bila kamu memilih bahasa lain di menu pemutar.
     */
    var preferredAudioLang: String
        get() = prefs.getString("audio_lang", "id") ?: "id"
        set(v) = prefs.edit().putString("audio_lang", v).apply()

    /** Kode negara YouTube untuk rekomendasi (lihat [Regions]). */
    var region: String
        get() = prefs.getString("region", "ID") ?: "ID"
        set(v) = prefs.edit().putString("region", v).apply()

    /** Unduhan hanya boleh berjalan lewat Wi-Fi / jaringan tak berbayar. */
    var wifiOnlyDownload: Boolean
        get() = prefs.getBoolean("wifi_only_download", true)
        set(v) = prefs.edit().putBoolean("wifi_only_download", v).apply()
}

fun StreamInfo.toVideoItem(): VideoItem = VideoItem(
    url = url ?: "",
    title = name ?: "",
    channel = uploaderName ?: "",
    channelUrl = uploaderUrl,
    thumb = thumbnails.bestUrl(),
    durationSec = duration,
    views = viewCount,
    date = null
)

fun WatchLaterEntity.toVideo() = VideoItem(url, title, channel, channelUrl, thumbnail, duration, -1L, null)
fun PlaylistItemEntity.toVideo() = VideoItem(url, title, channel, channelUrl, thumbnail, duration, -1L, null)

/** Tonton nanti, playlist, dan unduhan. */
class Library(private val db: GatraDb) {

    // ----- Tonton nanti -----
    val watchLater: Flow<List<WatchLaterEntity>> = db.watchLater().all()

    fun isWatchLater(url: String): Flow<Boolean> = db.watchLater().count(url).map { it > 0 }

    suspend fun addWatchLater(v: VideoItem) {
        db.watchLater().insert(
            WatchLaterEntity(v.url, v.title, v.channel, v.channelUrl, v.thumb, v.durationSec, System.currentTimeMillis())
        )
    }

    suspend fun removeWatchLater(url: String) = db.watchLater().delete(url)

    // ----- Playlist -----
    val playlists: Flow<List<PlaylistEntity>> = db.playlists().all()
    val playlistCounts: Flow<Map<Long, Int>> = db.playlists().counts().map { l -> l.associate { it.playlistId to it.n } }

    suspend fun createPlaylist(name: String): Long =
        db.playlists().insert(PlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis()))

    suspend fun deletePlaylist(id: Long) {
        db.playlists().deleteItems(id)
        db.playlists().delete(id)
    }

    fun playlistItems(id: Long): Flow<List<PlaylistItemEntity>> = db.playlists().items(id)

    suspend fun addToPlaylist(id: Long, v: VideoItem) {
        db.playlists().addItem(
            PlaylistItemEntity(id, v.url, v.title, v.channel, v.channelUrl, v.thumb, v.durationSec, System.currentTimeMillis())
        )
    }

    suspend fun removeFromPlaylist(id: Long, url: String) = db.playlists().removeItem(id, url)

    // ----- Unduhan -----
    val downloads: Flow<List<DownloadEntity>> = db.downloads().all()

    suspend fun downloadFor(url: String): DownloadEntity? = db.downloads().get(url)
    suspend fun saveDownload(e: DownloadEntity) = db.downloads().upsert(e)
    suspend fun setDownloadProgress(url: String, done: Long, total: Long) = db.downloads().progress(url, done, total)
    suspend fun setDownloadStatus(url: String, status: Int) = db.downloads().setStatus(url, status)
    suspend fun deleteDownload(url: String) = db.downloads().delete(url)
}
