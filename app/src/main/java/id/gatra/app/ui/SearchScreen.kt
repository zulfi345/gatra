package id.gatra.app.ui

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.app.Application
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import id.gatra.app.GatraApp
import id.gatra.app.data.ChannelItem
import id.gatra.app.data.VideoItem
import id.gatra.app.data.friendlyError
import id.gatra.app.ui.theme.G
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as GatraApp).repo
    private val prefs = app.getSharedPreferences("gatra", Context.MODE_PRIVATE)

    val region get() = id.gatra.app.data.Regions.current

    var query by mutableStateOf("")
    var channelsMode by mutableStateOf(false)
        private set
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var channels by mutableStateOf<List<ChannelItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var searched by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var recent by mutableStateOf(loadRecent())
        private set

    private var handler: SearchQueryHandler? = null
    private var next: Page? = null
    private var job: Job? = null

    private fun loadRecent(): List<String> =
        (prefs.getString("recent", "") ?: "").split('\n').filter { it.isNotBlank() }

    private fun saveRecent(q: String) {
        val list = (listOf(q) + recent.filter { !it.equals(q, ignoreCase = true) }).take(10)
        recent = list
        prefs.edit().putString("recent", list.joinToString("\n")).apply()
    }

    fun clearRecent() {
        recent = emptyList()
        prefs.edit().remove("recent").apply()
    }

    fun submit() {
        val q = query.trim()
        if (q.isEmpty()) return
        saveRecent(q)
        run(q)
    }

    fun useRecent(q: String) {
        query = q
        submit()
    }

    fun setMode(channelsOnly: Boolean) {
        if (channelsMode == channelsOnly) return
        channelsMode = channelsOnly
        if (searched) submit()
    }

    private fun run(q: String) {
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            error = null
            searched = true
            try {
                val p = repo.search(q, channelsMode)
                videos = p.videos
                channels = p.channels
                handler = p.handler
                next = p.next
                hasMore = p.next != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                videos = emptyList()
                channels = emptyList()
                hasMore = false
                error = friendlyError(e)
            }
            loading = false
        }
    }

    fun loadMore() {
        val h = handler
        val n = next
        if (h == null || n == null || loadingMore) return
        viewModelScope.launch {
            loadingMore = true
            try {
                val p = repo.moreSearch(h, n)
                videos = videos + p.videos
                channels = channels + p.channels
                next = p.next
                hasMore = p.next != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e)
                hasMore = false
            }
            loadingMore = false
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onVideo: (String) -> Unit,
    onChannel: (String) -> Unit,
    vm: SearchViewModel = viewModel()
) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (vm.query.isEmpty()) requester.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = G.Text) }
            TextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                placeholder = { Text("Cari di Gatra", color = G.Muted) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submit(); focus.clearFocus() }),
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Close, "Hapus", tint = G.Muted) }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = G.SurfaceHigh,
                    unfocusedContainerColor = G.SurfaceHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    cursorColor = G.Gold
                ),
                modifier = Modifier
                    .weight(1f)
                    .border(1.5.dp, Brush.horizontalGradient(listOf(G.Cyan, G.Purple)), RoundedCornerShape(24.dp))
                    .focusRequester(requester)
            )
            Box(Modifier.padding(end = 8.dp))
        }

        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val colors = FilterChipDefaults.filterChipColors(
                containerColor = G.SurfaceHigh, labelColor = G.Text,
                selectedContainerColor = G.Gold, selectedLabelColor = G.OnGold
            )
            FilterChip(selected = !vm.channelsMode, onClick = { vm.setMode(false) }, label = { Text("Video") }, colors = colors)
            FilterChip(selected = vm.channelsMode, onClick = { vm.setMode(true) }, label = { Text("Channel") }, colors = colors)
        }

        val listState = rememberLazyListState()
        InfiniteScrollEffect(
            listState,
            enabled = vm.searched && vm.hasMore && !vm.loading && !vm.loadingMore,
            onLoadMore = vm::loadMore
        )
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            when {
                vm.loading -> item { LoadingBox() }
                vm.error != null && !vm.searched -> item { ErrorBox(vm.error ?: "") { vm.submit() } }
                !vm.searched -> {
                    item {
                        val r = vm.region
                        Text(
                            "Sedang tren ${r.flag} ${r.name}",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                        )
                        r.queries.forEachIndexed { i, q ->
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.useRecent(q); focus.clearFocus() }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(34.dp).clip(CircleShape).background(G.SurfaceHigh),
                                    contentAlignment = Alignment.Center
                                ) { Text("${i + 1}", color = G.Muted, fontWeight = FontWeight.SemiBold) }
                                Spacer(Modifier.width(14.dp))
                                Text(q, color = G.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (vm.recent.isNotEmpty()) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Pencarian terakhir", color = G.Muted, modifier = Modifier.weight(1f))
                                TextButton(onClick = vm::clearRecent) { Text("Hapus semua", color = G.Gold) }
                            }
                        }
                        item {
                            FlowRow(
                                Modifier.padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                vm.recent.forEach { q ->
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(G.SurfaceHigh)
                                            .clickable { vm.useRecent(q); focus.clearFocus() }
                                            .padding(horizontal = 14.dp, vertical = 8.dp)
                                    ) { Text(q, color = G.Text, fontSize = 14.sp) }
                                }
                            }
                        }
                    }
                }
                else -> {
                    if (vm.error != null && vm.videos.isEmpty() && vm.channels.isEmpty()) {
                        item { ErrorBox(vm.error ?: "") { vm.submit() } }
                    } else if (vm.videos.isEmpty() && vm.channels.isEmpty()) {
                        item { EmptyState("Tidak ada hasil", "Coba kata kunci lain.") }
                    } else {
                        items(vm.channels) { c -> ChannelRow(c) { onChannel(c.url) } }
                        items(vm.videos) { v ->
                            CompactVideoRow(v, onClick = { onVideo(v.url) })
                        }
                        if (vm.loadingMore) item { LoadMoreSpinner() }
                    }
                }
            }
        }
    }
}
