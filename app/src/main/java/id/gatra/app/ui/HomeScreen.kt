package id.gatra.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    var discover by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var catItems by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val recs: StateFlow<List<VideoItem>> =
        repo.recommendations.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val cache = HashMap<Int, List<VideoItem>>()

    init {
        refresh()
    }

    fun refresh() {
        cache.clear()
        val index = selected
        viewModelScope.launch {
            loading = true
            error = null
            if (index == 0) loadHome() else loadCategory(index)
            loading = false
        }
    }

    fun select(i: Int) {
        selected = i
        error = null
        if (i == 0) {
            if (feed.isEmpty() && discover.isEmpty() && recs.value.isEmpty()) refresh()
            return
        }
        cache[i]?.let { catItems = it; return }
        catItems = emptyList()
        viewModelScope.launch {
            loading = true
            loadCategory(i)
            loading = false
        }
    }

    private suspend fun loadHome() {
        try {
            feed = repo.feed()
            if (feed.isEmpty() && recs.value.isEmpty()) {
                discover = repo.discover()
                if (discover.isEmpty()) error = "Belum ada video yang bisa dimuat. Periksa internet lalu coba lagi."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
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
                catItems = emptyList()
                error = friendlyError(e)
            }
        }
    }
}

@Composable
fun HomeScreen(
    onVideo: (String) -> Unit,
    onChannel: (String) -> Unit,
    onSearch: () -> Unit,
    vm: HomeViewModel = viewModel()
) {
    val recs by vm.recs.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        GatraTopBar(
            leading = { Wordmark() },
            actions = {
                IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Muat ulang", tint = G.Text) }
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Cari", tint = G.Text) }
            }
        )
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
                    itemsIndexed(recList) { _, v -> VideoCard(v, { onVideo(v.url) }) }
                }
                if (feedTop.isEmpty() && recList.isEmpty()) {
                    when {
                        vm.loading -> item { LoadingBox() }
                        vm.discover.isNotEmpty() -> {
                            item { SectionTitle("Jelajahi") }
                            itemsIndexed(vm.discover) { _, v -> VideoCard(v, { onVideo(v.url) }) }
                            item {
                                EmptyState(
                                    "Beranda akan menyesuaikan diri",
                                    "Tonton video atau tekan Langganan di halaman channel. Rekomendasi di sini akan makin cocok dengan seleramu."
                                )
                            }
                        }
                        vm.error != null -> item { ErrorBox(vm.error ?: "", vm::refresh) }
                    }
                } else if (vm.loading) {
                    item { LoadingBox() }
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
