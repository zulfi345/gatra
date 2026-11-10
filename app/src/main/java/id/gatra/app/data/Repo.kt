package id.gatra.app.data

import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
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
    val next: Page?
)

class MoreVideos(val videos: List<VideoItem>, val next: Page?)

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
    val recommendations: Flow<List<VideoItem>> = db.recs().visible().map { list ->
        list.map {
            VideoItem(
                url = it.url, title = it.title, channel = it.channel, channelUrl = null,
                thumb = it.thumbnail, durationSec = it.duration, views = it.views, date = null
            )
        }
    }

    fun isSubscribed(channelUrl: String): Flow<Boolean> =
        db.subs().count(channelIdOf(channelUrl) ?: "-", channelUrl).map { it > 0 }

    suspend fun subscribe(channelUrl: String, name: String, avatar: String?) = withContext(Dispatchers.IO) {
        val id = channelIdOf(channelUrl) ?: ChannelInfo.getInfo(yt, channelUrl).id
        db.subs().insert(SubscriptionEntity(id, name, channelUrl, avatar, System.currentTimeMillis()))
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
                        views = r.viewCount, savedAt = now, rank = i
                    )
                }
            )
            db.recs().prune()
        }
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
                .map { it.toVideo(info.name, info.url) }
            next = if (tabInfo.hasNextPage()) tabInfo.nextPage else null
        }
        ChannelPage(
            id = info.id, name = info.name ?: "", url = info.url ?: url,
            avatar = info.avatars.bestUrl(), subscribers = info.subscriberCount,
            description = info.description, videos = videos, tab = tab, next = next
        )
    }

    suspend fun moreChannel(tab: ListLinkHandler, page: Page, name: String, url: String): MoreVideos =
        withContext(Dispatchers.IO) {
            val p = ChannelTabInfo.getMoreItems(yt, tab, page)
            MoreVideos(
                p.items.filterIsInstance<StreamInfoItem>().map { it.toVideo(name, url) },
                if (p.hasNextPage()) p.nextPage else null
            )
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
        }.sortedByDescending { it.publishedMs }.take(60)
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
                        publishedMs = published
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
