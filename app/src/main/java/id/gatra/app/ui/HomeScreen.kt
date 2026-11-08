package id.gatra.app.ui

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
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
import id.gatra.app.data.VideoItem
import id.gatra.app.data.friendlyError
import id.gatra.app.ui.theme.G
import id.gatra.app.ui.theme.Wordmark
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
    private val queries = listOf(
        null, "musik indonesia terbaru", "gameplay game seru", "berita terkini hari ini",
        "highlight olahraga", "review teknologi terbaru", "resep masakan",
        "trailer film terbaru", "belajar pengetahuan umum"
    )

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
            if (index == 0) loadHome(force = true) else loadCategory(index)
            refreshing = false
            loading = false
        }
    }

    fun select(i: Int) {
        selected = i
        error = null
        if (i == 0) return
        cache[i]?.let { catItems = it; return }
        catItems = emptyList()
        viewModelScope.launch {
            loading = true
            loadCategory(i)
            loading = false
        }
    }

    private suspend fun loadHome(force: Boolean) {
        try {
            feed = repo.feed()
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
        val q = queries[i] ?: return
        try {
            val r = repo.search(q, false).videos
            cache[i] = r
            if (selected == i) catItems = r
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

    Column(Modifier.fillMaxSize()) {
        GatraTopBar(
            leading = { Wordmark() },
            actions = {
                IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Muat ulang", tint = G.Text) }
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Cari", tint = G.Text) }
            }
        )
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { CategoryChips(vm.labels, vm.selected, vm::select) }

                if (vm.selected == 0) {
                    val feedTop = vm.feed.take(8)
                    val feedUrls = feedTop.map { it.url }.toSet()
                    val recList = recs.filter { it.url !in feedUrls }

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
                        else -> itemsIndexed(vm.catItems) { _, v ->
                            VideoCard(v, { onVideo(v.url) }, v.channelUrl?.let { u -> { onChannel(u) } })
                        }
                    }
                }
            }
        }
    }
}
