package id.gatra.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.gatra.app.GatraApp
import id.gatra.app.data.ChannelPage
import id.gatra.app.data.VideoItem
import id.gatra.app.data.YtPlaylist
import id.gatra.app.data.compactCount
import id.gatra.app.data.friendlyError
import id.gatra.app.ui.theme.G
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

/** Isi satu tab channel (Video, Live, atau Playlist) beserta status pemuatannya. */
private class TabState {
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
    var playlists by mutableStateOf<List<YtPlaylist>>(emptyList())
    var next by mutableStateOf<Page?>(null)
    var loaded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

private val TAB_LABELS = listOf("Video", "Live", "Playlist")

@Composable
fun ChannelScreen(
    url: String,
    onBack: () -> Unit,
    onVideo: (String) -> Unit,
    onPlaylist: (String) -> Unit
) {
    val app = LocalContext.current.applicationContext as GatraApp
    val repo = app.repo
    val scope = rememberCoroutineScope()

    var page by remember(url) { mutableStateOf<ChannelPage?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    var descExpanded by remember(url) { mutableStateOf(false) }
    var tabIndex by remember(url) { mutableIntStateOf(0) }
    var tabAttempt by remember(url) { mutableIntStateOf(0) }
    val tabs = remember(url) { List(3) { TabState() } }
    val listState = rememberLazyListState()

    fun handlerFor(p: ChannelPage, i: Int): ListLinkHandler? = when (i) {
        0 -> p.tab
        1 -> p.liveTab
        else -> p.playlistTab
    }

    LaunchedEffect(url, attempt) {
        loading = true
        error = null
        try {
            val p = repo.channel(url)
            page = p
            tabs[0].videos = p.videos
            tabs[0].next = p.next
            tabs[0].loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
        }
        loading = false
    }

    val channelUrl = page?.url ?: url
    val subscribed by remember(channelUrl) { repo.isSubscribed(channelUrl) }.collectAsStateWithLifecycle(false)

    // Tab Live / Playlist baru dimuat saat dibuka pertama kali.
    LaunchedEffect(page, tabIndex, tabAttempt) {
        val p = page ?: return@LaunchedEffect
        val t = tabs[tabIndex]
        if (t.loaded || t.loading) return@LaunchedEffect
        val handler = handlerFor(p, tabIndex)
        if (handler == null) {
            t.loaded = true
            return@LaunchedEffect
        }
        t.loading = true
        t.error = null
        try {
            val r = repo.tabFirst(handler, p.name, channelUrl, p.avatar)
            t.videos = r.videos
            t.playlists = r.playlists
            t.next = r.next
            t.loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            t.error = friendlyError(e)
        }
        t.loading = false
    }

    // Gulir tanpa batas untuk tab yang sedang dibuka.
    val cur = tabs[tabIndex]
    InfiniteScrollEffect(
        listState,
        enabled = page != null && cur.loaded && cur.next != null && !cur.loadingMore && !cur.loading
    ) {
        val p = page
        val h = if (p != null) handlerFor(p, tabIndex) else null
        val n = cur.next
        if (p != null && h != null && n != null) {
            scope.launch {
                cur.loadingMore = true
                try {
                    val r = repo.tabMore(h, n, p.name, channelUrl, p.avatar)
                    val have = cur.videos.map { it.url }.toSet()
                    cur.videos = cur.videos + r.videos.filter { it.url !in have }
                    cur.playlists = cur.playlists + r.playlists
                    cur.next = r.next
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    cur.next = null
                }
                cur.loadingMore = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        GatraTopBar(
            leading = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = G.Text) }
            }
        )
        val p = page
        when {
            loading && p == null -> LoadingBox()
            error != null && p == null -> ErrorBox(error ?: "") { attempt++ }
            p != null -> {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            if (p.banner != null) {
                                coil.compose.AsyncImage(
                                    model = p.banner,
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 5.5f)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                                        .background(G.SurfaceHigh)
                                )
                                Spacer(Modifier.height(14.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(96.dp)
                                        .border(3.dp, G.Brand, androidx.compose.foundation.shape.CircleShape)
                                        .padding(5.dp)
                                ) { InitialAvatar(p.name, 86.dp, imageUrl = p.avatar) }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (p.subscribers >= 0) {
                                        Text("${compactCount(p.subscribers)} pelanggan", color = G.Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            val toggleSub = {
                                scope.launch {
                                    try {
                                        if (subscribed) repo.unsubscribe(channelUrl)
                                        else repo.subscribe(channelUrl, p.name, p.avatar)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                    }
                                }
                                Unit
                            }
                            if (subscribed) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                                        .background(G.SurfaceHigh)
                                        .clickable { toggleSub() }
                                        .padding(vertical = 13.dp),
                                    contentAlignment = Alignment.Center
                                ) { Text("BERLANGGANAN", color = G.Muted, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                            } else {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                                        .background(G.BrandButton)
                                        .clickable { toggleSub() }
                                        .padding(vertical = 13.dp),
                                    contentAlignment = Alignment.Center
                                ) { Text("LANGGANAN", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                            }

                            if (!p.description.isNullOrBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    p.description,
                                    color = G.Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = if (descExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { descExpanded = !descExpanded }
                                )
                            }
                        }
                    }

                    item {
                        CategoryChips(TAB_LABELS, tabIndex) { i ->
                            tabIndex = i
                            scope.launch { if (listState.firstVisibleItemIndex > 1) listState.scrollToItem(1) }
                        }
                    }

                    val empty = cur.videos.isEmpty() && cur.playlists.isEmpty()
                    when {
                        !cur.loaded && cur.error == null -> item { LoadingBox() }
                        cur.error != null && empty -> item {
                            ErrorBox(cur.error ?: "") {
                                cur.error = null
                                tabAttempt++
                            }
                        }
                        tabIndex == 2 -> {
                            if (cur.playlists.isEmpty()) {
                                item { EmptyState("Belum ada playlist", "Channel ini belum punya playlist yang bisa ditampilkan.") }
                            } else {
                                items(cur.playlists) { pl -> YtPlaylistRow(pl) { onPlaylist(pl.url) } }
                            }
                        }
                        else -> {
                            if (cur.videos.isEmpty()) {
                                item {
                                    if (tabIndex == 1) {
                                        EmptyState("Tidak ada siaran langsung", "Channel ini tidak sedang atau belum pernah siaran langsung.")
                                    } else {
                                        EmptyState("Belum ada video", "Channel ini belum punya video yang bisa ditampilkan.")
                                    }
                                }
                            } else {
                                items(cur.videos) { v -> VideoCard(v, { onVideo(v.url) }) }
                            }
                        }
                    }
                    if (cur.loadingMore) item { LoadMoreSpinner() }
                }
            }
        }
    }
}
