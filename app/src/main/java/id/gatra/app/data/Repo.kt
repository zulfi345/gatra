package id.gatra.app.data

import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.OffsetDateTime

class SearchPage(
    val videos: List<VideoItem>,
    val channels: List<ChannelItem>,
    val handler: SearchQueryHandler?,
    val next: Page?
)

class ChannelPage(
    val id: String,
    val name: String,
    val url: String,
    val avatar: String?,
    val subscribers: Long,
    val description: String?,
    val videos: List<VideoItem>,
    val tab: ListLinkHandler?,
    val next: Page?,
    val liveTab: ListLinkHandler? = null,
    val playlistTab: ListLinkHandler? = null,
    val banner: String? = null
)

class MoreVideos(val videos: List<VideoItem>, val next: Page?)

/** Satu halaman isi tab channel: video/live atau playlist, tergantung tab-nya. */
class TabPage(val videos: List<VideoItem>, val playlists: List<YtPlaylist>, val next: Page?)

class PlaylistPage(
    val title: String,
    val uploader: String,
    val count: Long,
    val thumb: String?,
    val videos: List<VideoItem>,
    val next: Page?
)

/**
 * Pembaca hasil pencarian bertahap untuk gulir tanpa batas: panggil [next] berulang,
 * tiap panggilan mengambil halaman hasil berikutnya.
 */
class VideoCursor(private val repo: Repo, val query: String) {
    private var handler: SearchQueryHandler? = null
    private var nextPage: Page? = null
    private var started = false

    val hasMore: Boolean get() = !started || nextPage != null

    suspend fun next(): List<VideoItem> {
        if (!hasMore) return emptyList()
        val h = handler
        val n = nextPage
        val page = if (!started || h == null || n == null) repo.search(query, false) else repo.moreSearch(h, n)
        started = true
        handler = page.handler
        nextPage = page.next
        return page.videos
    }
}

/** Satu kandidat rekomendasi: video + genre (dari kueri) + asal. */
private class PoolItem(val v: VideoItem, val genre: String?, val source: String?)

private inline fun <T> safe(default: T, block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        default
    }

class Repo(private val db: GatraDb, private val http: OkHttpClient) {

    private val yt get() = ServiceList.YouTube

    // ---------- Data lokal ----------

    val subscriptions: Flow<List<SubscriptionEntity>> = db.subs().all()
    val history: Flow<List<HistoryEntity>> = db.history().all()

    /** Rekomendasi Beranda, diurutkan oleh algoritma lokal [Ranking] (otomatis menyusun ulang saat minatmu berubah). */
    val recommendations: Flow<List<VideoItem>> =
        combine(db.recs().pool(), db.signals().allFlow(), db.history().all(), db.subs().all()) { pool, sigs, hist, subs ->
            val since = System.currentTimeMillis() - 48L * 3_600_000L
            val recent = hist.filter { it.watchedAt >= since }
                .groupingBy { Ranking.channelKey(it.channelUrl, it.channel) }
                .eachCount()
            val subKeys = subs.map { Ranking.channelKey(it.url, it.name) }.toSet()
            Ranking.rank(pool, sigs, recent, hist, subKeys)
        }.flowOn(Dispatchers.Default)

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------- Minat (sinyal untuk algoritma) ----------

    private suspend fun bump(name: String, delta: Double) {
        val now = System.currentTimeMillis()
        val old = db.signals().get(name)
        val base = if (old == null) 0.0 else Ranking.decayed(old.weight, old.updatedAt, now)
        db.signals().upsert(SignalEntity(name, (base + delta).coerceIn(-12.0, 12.0), now))
    }

    private suspend fun bumpVideo(channelUrl: String?, channel: String, title: String, chDelta: Double, kwDelta: Double) {
        bump(Ranking.channelKey(channelUrl, channel), chDelta)
        for (k in Ranking.keywords(title)) bump("kw:$k", kwDelta)
        Genres.classify(title, channel)?.let { bump("g:$it", chDelta * 0.6) }
    }

    /**
     * Catat seberapa lama sebuah video ditonton. Ditutup sebelum 10 detik = sinyal negatif ringan;
     * makin lama / makin tuntas ditonton = makin positif.
     */
    fun noteWatch(info: StreamInfo, ratio: Double, watchedMs: Long) {
        bg.launch {
            try {
                val (ch, kw) = when {
                    watchedMs < 10_000 && ratio < 0.1 -> -1.0 to -0.5
                    ratio < 0.5 -> 0.5 to 0.3
                    ratio < 0.9 -> 1.5 to 0.8
                    else -> 3.0 to 1.5
                }
                bumpVideo(info.uploaderUrl, info.uploaderName ?: "", info.name ?: "", ch, kw)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Tindakan jelas: simpan/unduh/playlist (positif), "Tidak tertarik" (negatif). [strength] = kekuatan sinyal. */
    fun noteAction(v: VideoItem, strength: Double) {
        bg.launch {
            try {
                bumpVideo(v.channelUrl, v.channel, v.title, strength, strength * 0.5)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Sinyal untuk satu channel saja (misalnya "Jangan rekomendasikan channel"). */
    fun noteChannel(channelUrl: String?, channel: String, strength: Double) {
        bg.launch {
            try {
                bump(Ranking.channelKey(channelUrl, channel), strength)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Hapus semua minat yang sudah dipelajari (rekomendasi mulai dari nol). */
    suspend fun clearRecommendations() = withContext(Dispatchers.IO) { db.recs().clear() }

    suspend fun resetSignals() = withContext(Dispatchers.IO) { db.signals().clear() }

    fun isSubscribed(channelUrl: String): Flow<Boolean> =
        db.subs().count(channelIdOf(channelUrl) ?: "-", channelUrl).map { it > 0 }

    suspend fun subscribe(channelUrl: String, name: String, avatar: String?) = withContext(Dispatchers.IO) {
        val id = channelIdOf(channelUrl) ?: ChannelInfo.getInfo(yt, channelUrl).id
        db.subs().insert(SubscriptionEntity(id, name, channelUrl, avatar, System.currentTimeMillis()))
        bump(Ranking.channelKey(channelUrl, name), 4.0)
    }

    suspend fun unsubscribe(channelUrl: String) = withContext(Dispatchers.IO) {
        db.subs().delete(channelIdOf(channelUrl) ?: "-", channelUrl)
    }

    suspend fun removeHistory(url: String) = withContext(Dispatchers.IO) { db.history().delete(url) }
    suspend fun clearHistory() = withContext(Dispatchers.IO) { db.history().clear() }

    /** Dipanggil saat video mulai diputar: simpan riwayat + video terkait sebagai rekomendasi. */
    suspend fun onWatched(info: StreamInfo) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.history().upsert(
            HistoryEntity(
                url = info.url,
                title = info.name ?: "",
                channel = info.uploaderName ?: "",
                channelUrl = info.uploaderUrl ?: "",
                thumbnail = info.thumbnails.bestUrl(),
                duration = info.duration,
                watchedAt = now
            )
        )
        // Video terkait biasanya didominasi channel yang sama: batasi supaya rekomendasi tetap beragam.
        val watchedKey = Ranking.channelKey(info.uploaderUrl, info.uploaderName ?: "")
        var sameAsWatched = 0
        val related = capPerKey(info.relatedItems.filterIsInstance<StreamInfoItem>().take(30), 3) {
            Ranking.channelKey(it.uploaderUrl, it.uploaderName ?: "")
        }.filter {
            if (Ranking.channelKey(it.uploaderUrl, it.uploaderName ?: "") == watchedKey) {
                sameAsWatched++
                sameAsWatched <= 2
            } else {
                true
            }
        }.take(12)
        if (related.isNotEmpty()) {
            db.recs().insertAll(
                related.mapIndexed { i, r ->
                    RecEntity(
                        url = r.url, title = r.name ?: "", channel = r.uploaderName ?: "",
                        thumbnail = r.thumbnails.bestUrl(), duration = r.duration,
                        views = r.viewCount, savedAt = now, rank = i,
                        channelUrl = r.uploaderUrl, avatar = r.uploaderAvatars?.bestUrl(),
                        genre = Genres.classify(r.name ?: "", r.uploaderName ?: "")
                    )
                }
            )
            db.recs().prune()
        }
    }

    // ---------- Foto profil channel ----------

    private val avatarCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val avatarFailed = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val avatarGate = Semaphore(3)

    /** Foto profil asli sebuah channel (disimpan di memori; juga ke langganan bila channel itu diikuti). */
    suspend fun avatarFor(channelUrl: String): String? {
        avatarCache[channelUrl]?.let { return it }
        if (avatarFailed.containsKey(channelUrl)) return null
        return withContext(Dispatchers.IO) {
            val saved = db.subs().snapshot().firstOrNull { it.url == channelUrl }?.avatar
            if (saved != null) {
                avatarCache[channelUrl] = saved
                return@withContext saved
            }
            val fetched = avatarGate.withPermit {
                safe<String?>(null) { ChannelInfo.getInfo(yt, channelUrl).avatars?.bestUrl() }
            }
            if (fetched != null) {
                avatarCache[channelUrl] = fetched
                db.subs().setAvatar(channelIdOf(channelUrl) ?: "-", channelUrl, fetched)
            } else {
                avatarFailed[channelUrl] = true
            }
            fetched
        }
    }

    /** Isi foto profil untuk langganan yang belum punya (misalnya hasil impor CSV). */
    suspend fun backfillAvatars() = withContext(Dispatchers.IO) {
        val missing = db.subs().snapshot().filter { it.avatar == null }.take(12)
        if (missing.isEmpty()) return@withContext
        val gate = Semaphore(3)
        coroutineScope {
            missing.map { s ->
                async {
                    gate.withPermit {
                        safe<Unit>(Unit) {
                            val a = ChannelInfo.getInfo(yt, s.url).avatars?.bestUrl()
                            if (a != null) db.subs().setAvatar(s.channelId, s.url, a)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Rencana pencarian rekomendasi: pasangan (genre, kueri). Genre favoritmu dapat lebih banyak kueri,
     * ditambah genre bertetangga, genre baru, dan satu kueri dari channel/kata kunci favorit (tanpa genre).
     */
    private suspend fun buildPlan(): List<Pair<String?, String>> {
        // Di luar daerah asal: tampilkan yang populer di negara itu, tanpa dicampur selera dari riwayat.
        if (!Regions.isHome) return Regions.current.queries.shuffled().map { null to it }
        val now = System.currentTimeMillis()
        val day = (now / 86_400_000L).toInt()
        val signals = db.signals().all()
        val targets = Ranking.genreTargets(Ranking.genreProfile(db.history().recent(), signals, now), now)
        val plan = ArrayList<Pair<String?, String>>()
        if (targets.isEmpty()) {
            // Riwayat belum cukup: campuran beberapa genre.
            for (g in Genres.all.shuffled().take(6)) plan += g.id to Genres.pickQuery(g, day, 0)
        } else {
            for (entry in targets.entries.sortedByDescending { it.value }) {
                val g = Genres.byId(entry.key) ?: continue
                val n = if (entry.value >= 0.30) 2 else 1
                for (i in 0 until n) plan += g.id to Genres.pickQuery(g, day, i)
            }
        }
        val sigs = signals.map { it to Ranking.decayed(it.weight, it.updatedAt, now) }
        val topChannel = sigs.filter { it.first.name.startsWith("ch:") && it.second > 0.8 }
            .maxByOrNull { it.second }?.first?.name?.removePrefix("ch:")
            ?.let { rest -> if (rest.startsWith("http")) db.history().channelNameFor(rest) else rest }
        if (!topChannel.isNullOrBlank()) plan += null to topChannel
        val topKeywords = sigs.filter { it.first.name.startsWith("kw:") && it.second > 0.8 }
            .sortedByDescending { it.second }.take(2)
            .joinToString(" ") { it.first.name.removePrefix("kw:") }
        if (topKeywords.isNotBlank()) plan += null to topKeywords
        return plan.distinctBy { it.second }.take(9)
    }

    /** Dipakai gulir tanpa batas di Beranda: kueri dari rencana yang sama. */
    private suspend fun buildQueries(): List<String> = buildPlan().map { it.second }

    /**
     * Ambil video segar untuk "Rekomendasi untukmu": dicari dari channel yang sering kamu tonton
     * ditambah topik acak, lalu disimpan sehingga langsung tampil di beranda.
     */
    suspend fun refreshRecommendations(): Int = withContext(Dispatchers.IO) {
        val plan = buildPlan()
        val subs = db.subs().snapshot()
        val day = (System.currentTimeMillis() / 86_400_000L).toInt()
        // Video lama yang bagus dari channel langganan, bergilir tiap hari: pencarian nama channel
        // mengurutkan menurut relevansi, jadi hasilnya bukan hanya yang paling baru.
        val rediscover = if (subs.isEmpty()) {
            emptyList()
        } else {
            val sorted = subs.sortedBy { it.name.lowercase() }
            (0 until minOf(3, sorted.size)).map { sorted[(day * 3 + it).mod(sorted.size)] }
        }
        val (results, older) = coroutineScope {
            val byPlan = plan.map { (genre, q) ->
                async { genre to safe(emptyList<VideoItem>()) { search(q, false).videos.take(10) } }
            }
            val bySub = rediscover.map { s ->
                async {
                    safe(emptyList<VideoItem>()) {
                        val key = Ranking.channelKey(s.url, s.name)
                        search(s.name, false).videos
                            .filter { Ranking.channelKey(it.channelUrl, it.channel) == key }
                            .take(8)
                    }
                }
            }
            byPlan.awaitAll() to bySub.awaitAll()
        }
        // Setiap video membawa genre dari kueri yang menemukannya (kueri tanpa genre: ditebak dari judul).
        val labeled = results.map { (genre, videos) -> videos.map { PoolItem(it, genre, null) } }
        val olderItems = older.flatten().map { PoolItem(it, null, "sub") }
        val merged = (interleave(labeled) + olderItems).distinctBy { it.v.url }.take(90)
        upsertPool(merged)
        merged.size
    }

    /** Masukkan video terbaru dari channel langganan ke kumpulan kandidat, supaya dinilai bersama yang lain. */
    suspend fun addSubscriptionVideos(videos: List<VideoItem>) = withContext(Dispatchers.IO) {
        upsertPool(videos.take(80).map { PoolItem(it, null, "sub") })
    }

    /** Tandai video yang sudah ditawarkan di Beranda; yang berulang kali tidak dibuka diturunkan peringkatnya. */
    suspend fun markShown(urls: List<String>) = withContext(Dispatchers.IO) {
        if (urls.isNotEmpty()) db.recs().markShown(urls.take(40))
    }

    private suspend fun upsertPool(items: List<PoolItem>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val shown = db.recs().shownOf(items.map { it.v.url }).associate { it.url to it.shown }
        db.recs().insertAll(
            items.mapIndexed { i, item ->
                val v = item.v
                RecEntity(
                    url = v.url, title = v.title, channel = v.channel, thumbnail = v.thumb,
                    duration = v.durationSec, views = v.views, savedAt = now, rank = i,
                    channelUrl = v.channelUrl, avatar = v.avatar,
                    genre = item.genre ?: Genres.classify(v.title, v.channel),
                    published = if (v.publishedMs > 0L) v.publishedMs else parsePublishedMs(v.date, now),
                    shown = shown[v.url] ?: 0,
                    source = item.source
                )
            }
        )
        db.recs().prune()
    }

    // ---------- Pencarian ----------

    suspend fun search(query: String, channels: Boolean): SearchPage = withContext(Dispatchers.IO) {
        val filter = if (channels) "channels" else "videos"
        val handler = yt.searchQHFactory.fromQuery(query, listOf(filter), "")
        val info = SearchInfo.getInfo(yt, handler)
        toPage(info.relatedItems, handler, if (info.hasNextPage()) info.nextPage else null)
    }

    suspend fun moreSearch(handler: SearchQueryHandler, page: Page): SearchPage = withContext(Dispatchers.IO) {
        val p = SearchInfo.getMoreItems(yt, handler, page)
        toPage(p.items, handler, if (p.hasNextPage()) p.nextPage else null)
    }

    private fun toPage(items: List<InfoItem>, handler: SearchQueryHandler?, next: Page?): SearchPage {
        val videos = items.filterIsInstance<StreamInfoItem>().map { it.toVideo() }
        val channels = items.filterIsInstance<ChannelInfoItem>().map { it.toChannel() }
        return SearchPage(videos, channels, handler, next)
    }

    // ---------- Channel ----------

    suspend fun channel(url: String): ChannelPage = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(yt, url)
        val tab = info.tabs.firstOrNull { it.contentFilters.contains("videos") } ?: info.tabs.firstOrNull()
        var videos: List<VideoItem> = emptyList()
        var next: Page? = null
        if (tab != null) {
            val tabInfo = ChannelTabInfo.getInfo(yt, tab)
            videos = tabInfo.relatedItems.filterIsInstance<StreamInfoItem>()
                .map { it.toVideo(info.name, info.url, info.avatars?.bestUrl()) }
            next = if (tabInfo.hasNextPage()) tabInfo.nextPage else null
        }
        ChannelPage(
            id = info.id, name = info.name ?: "", url = info.url ?: url,
            avatar = info.avatars.bestUrl(), subscribers = info.subscriberCount,
            description = info.description, videos = videos, tab = tab, next = next,
            liveTab = info.tabs.firstOrNull { it.contentFilters.contains("livestreams") },
            playlistTab = info.tabs.firstOrNull { it.contentFilters.contains("playlists") },
            banner = info.banners.maxByOrNull { it.width }?.url
        )
    }

    suspend fun moreChannel(
        tab: ListLinkHandler, page: Page, name: String, url: String, avatar: String? = null
    ): MoreVideos =
        withContext(Dispatchers.IO) {
            val p = ChannelTabInfo.getMoreItems(yt, tab, page)
            MoreVideos(
                p.items.filterIsInstance<StreamInfoItem>().map { it.toVideo(name, url, avatar) },
                if (p.hasNextPage()) p.nextPage else null
            )
        }

    /** Halaman pertama sebuah tab channel (Video, Live, atau Playlist). */
    suspend fun tabFirst(tab: ListLinkHandler, name: String, url: String, avatar: String?): TabPage =
        withContext(Dispatchers.IO) {
            val ti = ChannelTabInfo.getInfo(yt, tab)
            toTabPage(ti.relatedItems, name, url, avatar, if (ti.hasNextPage()) ti.nextPage else null)
        }

    suspend fun tabMore(tab: ListLinkHandler, page: Page, name: String, url: String, avatar: String?): TabPage =
        withContext(Dispatchers.IO) {
            val p = ChannelTabInfo.getMoreItems(yt, tab, page)
            toTabPage(p.items, name, url, avatar, if (p.hasNextPage()) p.nextPage else null)
        }

    private fun toTabPage(items: List<InfoItem>, name: String, url: String, avatar: String?, next: Page?): TabPage =
        TabPage(
            items.filterIsInstance<StreamInfoItem>().map { it.toVideo(name, url, avatar) },
            items.filterIsInstance<PlaylistInfoItem>().map { it.toYtPlaylist() },
            next
        )

    // ---------- Playlist YouTube ----------

    suspend fun playlist(url: String): PlaylistPage = withContext(Dispatchers.IO) {
        val info = PlaylistInfo.getInfo(yt, url)
        PlaylistPage(
            title = info.name ?: "",
            uploader = info.uploaderName ?: "",
            count = info.streamCount,
            thumb = info.thumbnails?.bestUrl(),
            videos = info.relatedItems.filterIsInstance<StreamInfoItem>()
                .map { it.toVideo(info.uploaderName, info.uploaderUrl) },
            next = if (info.hasNextPage()) info.nextPage else null
        )
    }

    suspend fun morePlaylist(url: String, page: Page): MoreVideos = withContext(Dispatchers.IO) {
        val p = PlaylistInfo.getMoreItems(yt, url, page)
        MoreVideos(
            p.items.filterIsInstance<StreamInfoItem>().map { it.toVideo() },
            if (p.hasNextPage()) p.nextPage else null
        )
    }

    // ---------- Gulir tanpa batas di Beranda ----------

    /** Pembaca bertahap untuk topik/channel/kata kunci yang cocok dengan minatmu. */
    suspend fun exploreCursors(): List<VideoCursor> = buildQueries().map { VideoCursor(this, it) }

    /** Ambil halaman berikutnya dari semua pembaca; hanya video yang belum ada di [known]. */
    suspend fun exploreNext(cursors: List<VideoCursor>, known: Set<String>): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<VideoItem>()
            val seen = HashSet<String>(known)
            repeat(3) {
                if (out.size >= 12) return@repeat
                val active = cursors.filter { it.hasMore }
                if (active.isEmpty()) return@repeat
                val pages = coroutineScope {
                    active.map { c -> async { safe(emptyList<VideoItem>()) { c.next() } } }.awaitAll()
                }
                for (v in interleave(pages)) if (seen.add(v.url)) out += v
            }
            out
        }

    // ---------- Beranda: feed langganan (RSS resmi YouTube) ----------

    suspend fun feed(): List<VideoItem> = withContext(Dispatchers.IO) {
        val subs = db.subs().snapshot()
        if (subs.isEmpty()) return@withContext emptyList<VideoItem>()
        val gate = Semaphore(4)
        val all = coroutineScope {
            subs.map { s -> async { gate.withPermit { fetchSubVideos(s) } } }
                .awaitAll()
                .flatten()
        }.distinctBy { it.url }.sortedByDescending { it.publishedMs }.take(400)
        // Semua channel gagal dimuat (bukan "memang belum ada video"): beri tahu, jangan tampilkan layar kosong.
        if (all.isEmpty()) throw java.io.IOException("Video langganan gagal dimuat")
        all
    }

    /** RSS resmi YouTube dulu (dicoba 2 kali); bila tetap kosong, ambil dari halaman channel lewat extractor. */
    private fun fetchSubVideos(sub: SubscriptionEntity): List<VideoItem> {
        var r = safe<List<VideoItem>>(emptyList()) { fetchRss(sub) }
        if (r.isEmpty()) r = safe<List<VideoItem>>(emptyList()) { fetchRss(sub) }
        if (r.isEmpty()) r = safe<List<VideoItem>>(emptyList()) { fetchViaExtractor(sub) }
        return r
    }

    private fun fetchViaExtractor(sub: SubscriptionEntity): List<VideoItem> {
        val info = ChannelInfo.getInfo(yt, sub.url)
        val tab = info.tabs.firstOrNull { it.contentFilters.contains("videos") } ?: info.tabs.firstOrNull()
            ?: return emptyList()
        val tabInfo = ChannelTabInfo.getInfo(yt, tab)
        val avatar = sub.avatar ?: info.avatars?.bestUrl()
        val now = System.currentTimeMillis()
        return tabInfo.relatedItems.filterIsInstance<StreamInfoItem>().take(15).mapIndexed { i, item ->
            item.toVideo(sub.name, sub.url, avatar)
                .copy(publishedMs = approxPublishedMs(item.textualUploadDate, now, i))
        }
    }

    /** Waktu terbit dari teks seperti "3 hari yang lalu" / "3 days ago"; 0 bila teksnya tidak dikenali. */
    private fun parsePublishedMs(raw: String?, now: Long): Long {
        val m = Regex(
            """(\d+)\s*(detik|menit|jam|hari|minggu|bulan|tahun|second|sec|minute|min|hour|hr|day|week|wk|month|mo|year|yr)""",
            RegexOption.IGNORE_CASE
        ).find(raw.orEmpty()) ?: return 0L
        val n = m.groupValues[1].toLongOrNull() ?: 0L
        val unitMs = when (m.groupValues[2].lowercase()) {
            "detik", "second", "sec" -> 1_000L
            "menit", "minute", "min" -> 60_000L
            "jam", "hour", "hr" -> 3_600_000L
            "hari", "day" -> 86_400_000L
            "minggu", "week", "wk" -> 7 * 86_400_000L
            "bulan", "month", "mo" -> 30 * 86_400_000L
            else -> 365 * 86_400_000L
        }
        return (now - n * unitMs).coerceAtLeast(1L)
    }

    /** Perkiraan waktu terbit; bila teksnya tidak dikenali, urut berdasarkan posisi di halaman channel. */
    private fun approxPublishedMs(raw: String?, now: Long, index: Int): Long {
        val parsed = parsePublishedMs(raw, now)
        return if (parsed > 0L) parsed else (now - index * 6L * 3_600_000L).coerceAtLeast(1L)
    }

    /** Dipakai saat belum ada riwayat dan langganan: campuran beberapa topik populer. */
    suspend fun discover(): List<VideoItem> = coroutineScope {
        val queries = Regions.current.queries
        val results = queries.map { q ->
            async { safe(emptyList<VideoItem>()) { search(q, false).videos.take(6) } }
        }.awaitAll()
        interleave(results).distinctBy { it.url }
    }

    /** Simpan paling banyak [max] item untuk setiap kunci (mis. channel), urutan asli dipertahankan. */
    private fun <T> capPerKey(items: List<T>, max: Int, key: (T) -> String): List<T> {
        val seen = HashMap<String, Int>()
        return items.filter {
            val k = key(it)
            val n = seen[k] ?: 0
            if (n < max) {
                seen[k] = n + 1
                true
            } else {
                false
            }
        }
    }

    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        var i = 0
        while (true) {
            var any = false
            for (l in lists) if (i < l.size) { out += l[i]; any = true }
            if (!any) break
            i++
        }
        return out
    }

    private fun fetchRss(sub: SubscriptionEntity): List<VideoItem> {
        val req = Request.Builder()
            .url("https://www.youtube.com/feeds/videos.xml?channel_id=${sub.channelId}")
            .header("User-Agent", WEB_USER_AGENT)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val xml = resp.body?.string() ?: return emptyList()
            return parseRss(xml, sub)
        }
    }

    private fun parseTime(s: String): Long =
        try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (e: Exception) {
            0L
        }

    private fun parseRss(xml: String, sub: SubscriptionEntity): List<VideoItem> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val out = ArrayList<VideoItem>()
        var inEntry = false
        var id = ""
        var title = ""
        var published = 0L
        var views = -1L
        var link = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "entry" -> { inEntry = true; id = ""; title = ""; published = 0L; views = -1L; link = "" }
                    "yt:videoId" -> if (inEntry) id = parser.nextText().trim()
                    "title" -> if (inEntry && title.isEmpty()) title = parser.nextText().trim()
                    "published" -> if (inEntry) published = parseTime(parser.nextText().trim())
                    "link" -> if (inEntry && link.isEmpty()) link = parser.getAttributeValue(null, "href") ?: ""
                    "media:statistics" -> if (inEntry) views = parser.getAttributeValue(null, "views")?.toLongOrNull() ?: -1L
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "entry") {
                if (id.isNotEmpty() && !link.contains("/shorts/")) {
                    out += VideoItem(
                        url = "https://www.youtube.com/watch?v=$id",
                        title = title,
                        channel = sub.name,
                        channelUrl = sub.url,
                        thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                        durationSec = 0L,
                        views = views,
                        date = relativeTime(published),
                        publishedMs = published,
                        avatar = sub.avatar
                    )
                }
                inEntry = false
            }
            event = parser.next()
        }
        return out
    }

    // ---------- Ekspor / impor langganan (format sama dengan Google Takeout) ----------

    suspend fun exportCsv(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder("Channel ID,Channel URL,Channel title\n")
        db.subs().snapshot().forEach {
            sb.append(csv(it.channelId)).append(',').append(csv(it.url)).append(',').append(csv(it.name)).append('\n')
        }
        sb.toString()
    }

    suspend fun importCsv(text: String): Int = withContext(Dispatchers.IO) {
        var count = 0
        text.removePrefix("\uFEFF").lineSequence().drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val cols = parseCsvLine(line)
            val id = cols.getOrNull(0)?.trim().orEmpty()
            if (!id.startsWith("UC")) return@forEach
            val title = cols.getOrNull(2)?.trim().orEmpty().ifBlank { id }
            db.subs().insertIgnore(
                SubscriptionEntity(id, title, "https://www.youtube.com/channel/$id", null, System.currentTimeMillis())
            )
            count++
        }
        count
    }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    companion object {
        /** Ambil ID channel dari URL bentuk .../channel/UCxxxx (bentuk yang dipakai extractor). */
        fun channelIdOf(url: String): String? {
            val i = url.indexOf("/channel/")
            if (i < 0) return null
            return url.substring(i + 9).substringBefore('/').substringBefore('?').takeIf { it.isNotBlank() }
        }
    }
}
