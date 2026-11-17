package id.gatra.desktop

import id.gatra.app.data.GatraDownloader
import id.gatra.app.data.VideoItem
import id.gatra.app.data.toVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

class FeedPage(
    val videos: List<VideoItem>,
    val handler: SearchQueryHandler?,
    val next: Page?
)

/** Objek bersama: koneksi internet, extractor, dan server lokal untuk VLC. */
object Svc {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val proxy: StreamProxy by lazy { StreamProxy(http) }

    fun init() {
        NewPipe.init(GatraDownloader(http), Localization("id", "ID"), ContentCountry("ID"))
    }
}

object DesktopRepo {
    private val yt get() = ServiceList.YouTube

    suspend fun search(query: String): FeedPage = withContext(Dispatchers.IO) {
        val handler = yt.searchQHFactory.fromQuery(query, listOf("videos"), "")
        val info = SearchInfo.getInfo(yt, handler)
        toPage(info.relatedItems, handler, if (info.hasNextPage()) info.nextPage else null)
    }

    suspend fun more(handler: SearchQueryHandler, page: Page): FeedPage = withContext(Dispatchers.IO) {
        val p = SearchInfo.getMoreItems(yt, handler, page)
        toPage(p.items, handler, if (p.hasNextPage()) p.nextPage else null)
    }

    suspend fun streams(url: String): StreamInfo = withContext(Dispatchers.IO) {
        StreamInfo.getInfo(yt, url)
    }

    private fun toPage(items: List<InfoItem>, handler: SearchQueryHandler?, next: Page?): FeedPage {
        val videos = items.filterIsInstance<StreamInfoItem>().map { it.toVideo() }.distinctBy { it.url }
        return FeedPage(videos, handler, next)
    }
}
