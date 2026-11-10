package id.gatra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import id.gatra.app.GatraApp
import id.gatra.app.data.PlaylistPage
import id.gatra.app.data.VideoItem
import id.gatra.app.data.YtPlaylist
import id.gatra.app.data.compactCount
import id.gatra.app.data.friendlyError
import id.gatra.app.ui.theme.G
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

/** Baris playlist milik channel (tab Playlist). */
@Composable
fun YtPlaylistRow(p: YtPlaylist, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .width(150.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(G.SurfaceHigh)
        ) {
            if (p.thumb != null) {
                AsyncImage(
                    model = p.thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (p.count >= 0) {
                Text(
                    "${compactCount(p.count)} video",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                if (p.uploader.isNotBlank()) "Playlist • ${p.uploader}" else "Playlist",
                style = MaterialTheme.typography.bodySmall, color = G.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Isi sebuah playlist YouTube: daftar video dengan gulir tanpa batas dan tombol Putar semua. */
@Composable
fun YtPlaylistScreen(
    url: String,
    onBack: () -> Unit,
    onVideo: (String) -> Unit,
    onPlayAll: (List<VideoItem>) -> Unit
) {
    val repo = (LocalContext.current.applicationContext as GatraApp).repo
    val scope = rememberCoroutineScope()

    var page by remember(url) { mutableStateOf<PlaylistPage?>(null) }
    var videos by remember(url) { mutableStateOf<List<VideoItem>>(emptyList()) }
    var next by remember(url) { mutableStateOf<Page?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var loadingMore by remember(url) { mutableStateOf(false) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var attempt by remember(url) { mutableStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(url, attempt) {
        loading = true
        error = null
        try {
            val p = repo.playlist(url)
            page = p
            videos = p.videos
            next = p.next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
        }
        loading = false
    }

    InfiniteScrollEffect(listState, enabled = page != null && next != null && !loadingMore) {
        val n = next
        if (n != null) {
            scope.launch {
                loadingMore = true
                try {
                    val more = repo.morePlaylist(url, n)
                    val have = videos.map { it.url }.toSet()
                    videos = videos + more.videos.filter { it.url !in have }
                    next = more.next
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    next = null
                }
                loadingMore = false
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
            p != null -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        val meta = buildList {
                            if (p.uploader.isNotBlank()) add(p.uploader)
                            if (p.count >= 0) add("${compactCount(p.count)} video")
                        }.joinToString(" • ")
                        if (meta.isNotEmpty()) Text(meta, color = G.Muted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { onPlayAll(videos) },
                            enabled = videos.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = G.Gold, contentColor = G.OnGold)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Putar semua")
                        }
                    }
                }
                if (videos.isEmpty()) {
                    item { EmptyState("Playlist kosong", "Tidak ada video yang bisa ditampilkan di playlist ini.") }
                }
                items(videos) { v -> CompactVideoRow(v, onClick = { onVideo(v.url) }) }
                if (loadingMore) item { LoadMoreSpinner() }
            }
        }
    }
}
