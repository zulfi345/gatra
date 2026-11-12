package id.gatra.app.ui

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import id.gatra.app.GatraApp
import id.gatra.app.data.Region
import id.gatra.app.data.Regions
import id.gatra.app.data.VideoCursor
import id.gatra.app.data.VideoItem
import id.gatra.app.data.friendlyError
import id.gatra.app.ui.theme.G
import id.gatra.app.ui.theme.WordmarkWithPlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as GatraApp).repo

    val labels = listOf(
        "Untukmu", "Musik", "Game", "Berita", "Olahraga",
        "Teknologi", "Kuliner", "Film", "Edukasi"
    )
    /** Kueri tiap kategori mengikuti negara terpilih (indeks sama dengan [labels]). */
    private fun queryFor(i: Int): String? {
        val r = Regions.current
        if (r.code == "ID") {
            return listOf(
                null, "musik indonesia terbaru", "gameplay game seru", "berita terkini hari ini",
                "highlight olahraga", "review teknologi terbaru", "resep masakan",
                "trailer film terbaru", "belajar pengetahuan umum"
            ).getOrNull(i)
        }
        val e = r.english
        return when (i) {
            1 -> r.queries[1]
            2 -> r.queries[3]
            3 -> r.queries[2]
            4 -> "sports highlights $e"
            5 -> "tech review $e"
            6 -> "street food recipes $e"
            7 -> "movie trailer $e"
            8 -> "education learning $e"
            else -> null
        }
    }

    var region by mutableStateOf(Regions.current)
        private set

    /** Ganti negara rekomendasi: simpan, pasang ke NewPipe, hapus rekomendasi lama, lalu muat ulang. */
    fun changeRegion(code: String) {
        if (code == region.code) return
        val app = getApplication<GatraApp>()
        app.settings.region = code
        Regions.apply(code)
        region = Regions.current
        viewModelScope.launch {
            repo.clearRecommendations()
            feedReset()
            refresh()
        }
    }

    private fun feedReset() {
        more = emptyList()
        catItems = emptyList()
        cache.clear()
    }

    var selected by mutableStateOf(0)
        private set
    var feed by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var catItems by mutableStateOf<List<VideoItem>>(emptyList())
        private set

    /** Pemuatan pertama (layar masih kosong). */
    var loading by mutableStateOf(true)
        private set

    /** Sedang menyegarkan (tarik layar atau tombol muat ulang). */
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Pesan singkat (toast) bila penyegaran gagal padahal isi lama masih tampil. */
    var message by mutableStateOf<String?>(null)

    val recs: StateFlow<List<VideoItem>> =
        repo.recommendations.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val cache = HashMap<Int, List<VideoItem>>()

    // ---- Gulir tanpa batas ----
    /** Video tambahan di bawah rekomendasi (Untukmu), terus bertambah saat daftar digulir. */
    var more by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loadingMore by mutableStateOf(false)
        private set
    private var homeHasMore by mutableStateOf(true)
    private var catHasMore by mutableStateOf(true)
    private var exploreCursors: List<VideoCursor> = emptyList()
    private val catCursors = HashMap<Int, VideoCursor>()

    /** True bila masih ada halaman berikutnya untuk tab yang sedang dibuka. */
    val canLoadMore: Boolean
        get() = !loading && !refreshing && !loadingMore && (if (selected == 0) homeHasMore else catHasMore)

    fun loadMore() {
        if (!canLoadMore) return
        val index = selected
        viewModelScope.launch {
            loadingMore = true
            try {
                if (index == 0) {
                    if (exploreCursors.isEmpty()) exploreCursors = repo.exploreCursors()
                    val known = HashSet<String>()
                    known += feed.map { it.url }
                    known += recs.value.map { it.url }
                    known += more.map { it.url }
                    val fresh = repo.exploreNext(exploreCursors, known)
                    more = more + fresh
                    if (fresh.isEmpty()) homeHasMore = false
                } else {
                    val cursor = catCursors[index]
                    val fresh = cursor?.next().orEmpty()
                    if (selected == index) {
                        val have = catItems.map { it.url }.toSet()
                        catItems = catItems + fresh.filter { it.url !in have }
                        cache[index] = catItems
                        catHasMore = fresh.isNotEmpty() && cursor?.hasMore == true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (index == 0) homeHasMore = false else catHasMore = false
            }
            loadingMore = false
        }
    }

    init {
        viewModelScope.launch {
            loading = true
            loadHome(force = false)
            loading = false
        }
    }

    /** Dipanggil oleh tarik-untuk-muat dan tombol muat ulang: selalu mengambil video baru. */
    fun refresh() {
        if (refreshing) return
        val index = selected
        viewModelScope.launch {
            refreshing = true
            error = null
            cache.clear()
            more = emptyList()
            exploreCursors = emptyList()
            catCursors.clear()
            homeHasMore = true
            catHasMore = true
            if (index == 0) loadHome(force = true) else loadCategory(index)
            refreshing = false
            loading = false
        }
    }

    fun select(i: Int) {
        selected = i
        error = null
        if (i == 0) return
        cache[i]?.let {
            catItems = it
            catHasMore = catCursors[i]?.hasMore ?: false
            return
        }
        catHasMore = true
        catItems = emptyList()
        viewModelScope.launch {
            loading = true
            loadCategory(i)
            loading = false
        }
    }

    private suspend fun loadHome(force: Boolean) {
        try {
            // Gagal memuat video langganan tidak boleh menghentikan pembaruan rekomendasi.
            feed = try {
                repo.feed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            var fresh = 0
            if (force || recs.value.isEmpty()) fresh = repo.refreshRecommendations()
            if (feed.isEmpty() && fresh == 0 && recs.value.isEmpty()) {
                error = "Belum ada video yang bisa dimuat. Periksa internet lalu coba lagi."
            }
            viewModelScope.launch { repo.backfillAvatars() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
            message = friendlyError(e)
        }
    }

    private suspend fun loadCategory(i: Int) {
        val q = queryFor(i) ?: return
        val cursor = VideoCursor(repo, q)
        catCursors[i] = cursor
        try {
            val r = cursor.next()
            cache[i] = r
            if (selected == i) {
                catItems = r
                catHasMore = cursor.hasMore
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (selected == i) {
                error = friendlyError(e)
                message = friendlyError(e)
            }
        }
    }
}

/** Pesan dari tab bawah ke layar Beranda: "tab Beranda ditekan". */
object HomeEvents {
    val pending = MutableStateFlow(false)
    fun request() {
        pending.value = true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onVideo: (String) -> Unit,
    onChannel: (String) -> Unit,
    onSearch: () -> Unit,
    vm: HomeViewModel = viewModel()
) {
    val recs by vm.recs.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }

    val listState = rememberLazyListState()
    var showRegions by remember { mutableStateOf(false) }
    val pendingHomeTap by HomeEvents.pending.collectAsStateWithLifecycle()

    // Tab Beranda ditekan: kembali ke "Untukmu", muat ulang, lalu naik ke paling atas.
    LaunchedEffect(pendingHomeTap) {
        if (pendingHomeTap) {
            HomeEvents.pending.value = false
            if (vm.selected != 0) vm.select(0)
            vm.refresh()
            listState.scrollToItem(0)
        }
    }

    // Setiap penyegaran (tarik layar maupun tab Beranda) selalu naik ke atas, saat mulai dan saat selesai.
    var wasRefreshing by remember { mutableStateOf(vm.refreshing) }
    LaunchedEffect(vm.refreshing) {
        if (vm.refreshing != wasRefreshing) {
            wasRefreshing = vm.refreshing
            listState.scrollToItem(0)
        }
    }

    InfiniteScrollEffect(listState, enabled = vm.canLoadMore, onLoadMore = vm::loadMore)

    val appCtx = LocalContext.current.applicationContext as GatraApp
    val hiddenVideos by appCtx.settings.hiddenVideos.collectAsStateWithLifecycle()
    val hiddenChannels by appCtx.settings.hiddenChannels.collectAsStateWithLifecycle()
    fun keep(v: VideoItem): Boolean =
        v.url !in hiddenVideos && (v.channelUrl ?: v.channel) !in hiddenChannels

    Column(Modifier.fillMaxSize()) {
        GatraTopBar(
            leading = { WordmarkWithPlay() },
            actions = {
                RegionButton(vm.region) { showRegions = true }
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Cari", tint = G.Text) }
            }
        )
        if (showRegions) {
            RegionDialog(vm.region, onPick = { vm.changeRegion(it); showRegions = false }, onDismiss = { showRegions = false })
        }
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                item { CategoryChips(vm.labels, vm.selected, vm::select) }

                if (vm.selected == 0) {
                    val feedTop = vm.feed.filter { keep(it) }.take(8)
                    val feedUrls = feedTop.map { it.url }.toSet()
                    val recList = recs.filter { keep(it) && it.url !in feedUrls }

                    if (feedTop.isNotEmpty()) {
                        item { SectionTitle("Terbaru dari langgananmu") }
                        itemsIndexed(feedTop) { _, v ->
                            VideoCard(v, { onVideo(v.url) }, v.channelUrl?.let { u -> { onChannel(u) } })
                        }
                    }
                    if (recList.isNotEmpty()) {
                        item { SectionTitle("Rekomendasi untukmu") }
                        itemsIndexed(recList) { _, v ->
                            VideoCard(v, { onVideo(v.url) }, v.channelUrl?.let { u -> { onChannel(u) } })
                        }
                    }
                    val moreList = vm.more.filter { keep(it) && it.url !in feedUrls }
                    if (moreList.isNotEmpty()) {
                        item { SectionTitle("Jelajahi lainnya") }
                        itemsIndexed(moreList) { _, v ->
                            VideoCard(v, { onVideo(v.url) }, v.channelUrl?.let { u -> { onChannel(u) } })
                        }
                    }
                    if (feedTop.isEmpty() && recList.isEmpty()) {
                        when {
                            vm.loading -> item { LoadingBox() }
                            vm.error != null -> item { ErrorBox(vm.error ?: "", vm::refresh) }
                            else -> item {
                                EmptyState(
                                    "Beranda akan menyesuaikan diri",
                                    "Tarik layar ke bawah untuk memuat video. Tonton video atau tekan Langganan di halaman channel supaya rekomendasi makin cocok."
                                )
                            }
                        }
                    }
                } else {
                    when {
                        vm.loading && vm.catItems.isEmpty() -> item { LoadingBox() }
                        vm.error != null && vm.catItems.isEmpty() -> item { ErrorBox(vm.error ?: "", vm::refresh) }
                        else -> itemsIndexed(vm.catItems.filter { keep(it) }) { _, v ->
                            VideoCard(v, { onVideo(v.url) }, v.channelUrl?.let { u -> { onChannel(u) } })
                        }
                    }
                }
                if (vm.loadingMore) item { LoadMoreSpinner() }
            }
        }
    }
}
