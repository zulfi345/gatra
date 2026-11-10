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
    val playlistTab: ListLinkHandler? = null
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
        combine(db.recs().pool(), db.signals().allFlow()) { pool, sigs -> Ranking.rank(pool, sigs) }
            .flowOn(Dispatchers.Default)

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
        val related = info.relatedItems.filterIsInstance<StreamInfoItem>().take(15)
        if (related.isNotEmpty()) {
            db.recs().insertAll(
                related.mapIndexed { i, r ->
                    RecEntity(
                        url = r.url, title = r.name ?: "", channel = r.uploaderName ?: "",
                        thumbnail = r.thumbnails.bestUrl(), duration = r.duration,
                        views = r.viewCount, savedAt = now, rank = i,
                        channelUrl = r.uploaderUrl, avatar = r.uploaderAvatars?.bestUrl()
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

    /** Susun daftar kueri pencarian dari minat yang dipelajari (atau topik acak bila belum ada). */
    private suspend fun buildQueries(): List<String> {
        val now = System.currentTimeMillis()
        val sigs = db.signals().all().map { it to Ranking.decayed(it.weight, it.updatedAt, now) }
        // Channel dan kata kunci yang paling kamu sukai saat ini.
        val topChannels = sigs.filter { it.first.name.startsWith("ch:") && it.second > 0.8 }
            .sortedByDescending { it.second }.take(2)
            .mapNotNull { (s, _) ->
                val rest = s.name.removePrefix("ch:")
                if (rest.startsWith("http")) db.history().channelNameFor(rest) else rest
            }
        val topKeywords = sigs.filter { it.first.name.startsWith("kw:") && it.second > 0.8 }
            .sortedByDescending { it.second }.map { it.first.name.removePrefix("kw:") }
        val keywordQueries = topKeywords.chunked(2).take(2).map { it.joinToString(" ") }
        val fallbackChannels =
            if (topChannels.isEmpty() && keywordQueries.isEmpty()) db.history().recentChannels().shuffled().take(3)
            else emptyList()
        val topics = listOf(
            "musik indonesia terbaru", "berita terkini hari ini", "video populer indonesia",
            "gameplay seru", "resep masakan", "review teknologi terbaru", "film pendek", "podcast indonesia"
        ).shuffled()
        val known = topChannels.isNotEmpty() || keywordQueries.isNotEmpty() || fallbackChannels.isNotEmpty()
        // Satu topik acak = jatah eksplorasi supaya Beranda tidak terjebak di satu selera.
        return (topChannels + keywordQueries + fallbackChannels + topics.take(if (known) 1 else 3)).distinct()
    }

    /**
     * Ambil video segar untuk "Rekomendasi untukmu": dicari dari channel yang sering kamu tonton
     * ditambah topik acak, lalu disimpan sehingga langsung tampil di beranda.
     */
    suspend fun refreshRecommendations(): Int = withContext(Dispatchers.IO) {
        val queries = buildQueries()
        val results = coroutineScope {
            queries.map { q ->
                async { safe(emptyList<VideoItem>()) { search(q, false).videos.take(8) } }
            }.awaitAll()
        }
        val merged = interleave(results).distinctBy { it.url }.take(40)
        if (merged.isNotEmpty()) {
            val now = System.currentTimeMillis()
            db.recs().insertAll(
                merged.mapIndexed { i, v ->
                    RecEntity(
                        url = v.url, title = v.title, channel = v.channel, thumbnail = v.thumb,
                        duration = v.durationSec, views = v.views, savedAt = now, rank = i,
                        channelUrl = v.channelUrl, avatar = v.avatar
                    )
                }
            )
            db.recs().prune()
        }
        merged.size
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
            playlistTab = info.tabs.firstOrNull { it.contentFilters.contains("playlists") }
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
        val gate = Semaphore(6)
        coroutineScope {
            subs.map { s -> async { gate.withPermit { safe<List<VideoItem>>(emptyList()) { fetchRss(s) } } } }
                .awaitAll()
                .flatten()
        }.sortedByDescending { it.publishedMs }.take(400)
    }

    /** Dipakai saat belum ada riwayat dan langganan: campuran beberapa topik populer. */
    suspend fun discover(): List<VideoItem> = coroutineScope {
        val queries = listOf("musik indonesia terbaru", "berita terkini hari ini", "video populer indonesia", "gameplay seru")
        val results = queries.map { q ->
            async { safe(emptyList<VideoItem>()) { search(q, false).videos.take(6) } }
        }.awaitAll()
        interleave(results).distinctBy { it.url }
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
