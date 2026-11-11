@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)

package id.gatra.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import id.gatra.app.data.VideoItem
import id.gatra.app.data.formatDuration
import id.gatra.app.data.friendlyError
import id.gatra.app.data.metaLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery

private class Category(val label: String, val query: String)

private val CATEGORIES = listOf(
    Category("Untukmu", "video populer indonesia"),
    Category("Musik", "musik indonesia terbaru"),
    Category("Game", "gameplay game populer"),
    Category("Berita", "berita terkini"),
    Category("Teknologi", "review teknologi gadget"),
    Category("Olahraga", "olahraga sepak bola highlight"),
    Category("Film", "trailer film terbaru"),
    Category("Kuliner", "resep masakan indonesia")
)

/** Daftar video di Beranda; disimpan di luar layar supaya tidak hilang saat membuka pemutar. */
private class FeedState(private val scope: CoroutineScope) {
    var catIndex by mutableStateOf(0)
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private var handler: SearchQueryHandler? = null
    private var next: Page? = null
    private var gen = 0

    fun load(query: String) {
        val my = ++gen
        videos = emptyList()
        error = null
        loading = true
        handler = null
        next = null
        scope.launch {
            try {
                val p = DesktopRepo.search(query)
                if (my != gen) return@launch
                videos = p.videos
                handler = p.handler
                next = p.next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (my == gen) error = friendlyError(e)
            } finally {
                if (my == gen) loading = false
            }
        }
    }

    fun more() {
        val h = handler
        val n = next
        if (h == null || n == null || loading) return
        val my = gen
        loading = true
        scope.launch {
            try {
                val p = DesktopRepo.more(h, n)
                if (my != gen) return@launch
                val seen = videos.map { it.url }.toSet()
                videos = videos + p.videos.filter { it.url !in seen }
                next = p.next
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (my == gen) next = null
            } finally {
                if (my == gen) loading = false
            }
        }
    }
}

fun main() {
    Svc.init()
    application {
        val state = rememberWindowState(width = 1280.dp, height = 820.dp)
        val icon = remember {
            runCatching { BitmapPainter(useResource("logo.png", ::loadImageBitmap)) }.getOrNull()
        }
        Window(
            onCloseRequest = {
                Svc.proxy.stop()
                exitApplication()
            },
            title = "Gatra",
            state = state,
            icon = icon
        ) {
            GatraTheme { GatraDesktopApp(state) }
        }
    }
}

@Composable
private fun GatraDesktopApp(windowState: WindowState) {
    val scope = rememberCoroutineScope()
    val feed = remember { FeedState(scope) }
    val gridState = rememberLazyGridState()
    var playing by remember { mutableStateOf<VideoItem?>(null) }
    var searchText by remember { mutableStateOf("") }
    val vlcOk = remember { runCatching { NativeDiscovery().discover() }.getOrDefault(false) }

    LaunchedEffect(Unit) {
        if (feed.videos.isEmpty() && !feed.loading) feed.load(CATEGORIES[0].query)
    }

    fun leavePlayer() {
        windowState.placement = WindowPlacement.Floating
        playing = null
    }

    Box(Modifier.fillMaxSize().background(G.Bg)) {
        val current = playing
        if (current != null) {
            PlayerScreen(
                video = current,
                vlcOk = vlcOk,
                fullscreen = windowState.placement == WindowPlacement.Fullscreen,
                onToggleFullscreen = {
                    windowState.placement =
                        if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating
                        else WindowPlacement.Fullscreen
                },
                onBack = { leavePlayer() }
            )
        } else {
            HomeScreen(
                feed = feed,
                gridState = gridState,
                searchText = searchText,
                onSearchText = { searchText = it },
                onSearch = {
                    val q = searchText.trim()
                    if (q.isNotEmpty()) {
                        feed.catIndex = -1
                        feed.load(q)
                    }
                },
                onPick = { index ->
                    feed.catIndex = index
                    searchText = ""
                    feed.load(CATEGORIES[index].query)
                },
                onOpen = { playing = it }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    feed: FeedState,
    gridState: LazyGridState,
    searchText: String,
    onSearchText: (String) -> Unit,
    onSearch: () -> Unit,
    onPick: (Int) -> Unit,
    onOpen: (VideoItem) -> Unit
) {
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            info.totalItemsCount to (info.visibleItemsInfo.lastOrNull()?.index ?: 0)
        }
            .filter { (total, last) -> total > 0 && last >= total - 6 }
            .collect { feed.more() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WordmarkWithPlay()
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = searchText,
                onValueChange = onSearchText,
                singleLine = true,
                placeholder = { Text("Cari video") },
                modifier = Modifier.width(420.dp).onPreviewKeyEvent { ev ->
                    if (ev.key == Key.Enter && ev.type == KeyEventType.KeyDown) {
                        onSearch()
                        true
                    } else {
                        false
                    }
                }
            )
            Spacer(Modifier.width(10.dp))
            Button(onClick = onSearch) { Text("Cari") }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        ) {
            itemsIndexed(CATEGORIES) { index, cat ->
                val on = index == feed.catIndex
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) G.Purple else G.SurfaceHigh)
                        .clickable { onPick(index) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(cat.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            val err = feed.error
            when {
                feed.videos.isEmpty() && feed.loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                feed.videos.isEmpty() && err != null ->
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(err, color = G.Muted)
                        Button(onClick = {
                            val q = searchText.trim()
                            if (q.isNotEmpty()) feed.load(q)
                            else feed.load(CATEGORIES[feed.catIndex.coerceAtLeast(0)].query)
                        }) { Text("Coba lagi") }
                    }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(300.dp),
                    state = gridState,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(feed.videos, key = { it.url }) { v ->
                        VideoCard(v) { onOpen(v) }
                    }
                    if (feed.loading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoCard(v: VideoItem, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
            NetImage(v.thumb, Modifier.fillMaxSize())
            val dur = formatDuration(v.durationSec)
            if (v.live) {
                Badge("LIVE", Modifier.align(Alignment.BottomEnd).padding(8.dp), G.Pink)
            } else if (dur.isNotEmpty()) {
                Badge(dur, Modifier.align(Alignment.BottomEnd).padding(8.dp), Color(0xCC000000))
            }
        }
        Row(Modifier.padding(top = 10.dp)) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(G.SurfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    v.channel.trim().firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                if (!v.avatar.isNullOrBlank()) NetImage(v.avatar, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    v.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = G.Text
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    v.metaLine(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    color = G.Muted
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier, bg: Color) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
