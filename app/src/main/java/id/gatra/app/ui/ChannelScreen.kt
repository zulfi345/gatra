package id.gatra.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.gatra.app.GatraApp
import id.gatra.app.data.ChannelPage
import id.gatra.app.data.VideoItem
import id.gatra.app.data.compactCount
import id.gatra.app.data.friendlyError
import id.gatra.app.ui.theme.G
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

@Composable
fun ChannelScreen(url: String, onBack: () -> Unit, onVideo: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as GatraApp
    val repo = app.repo
    val scope = rememberCoroutineScope()

    var page by remember(url) { mutableStateOf<ChannelPage?>(null) }
    var videos by remember(url) { mutableStateOf<List<VideoItem>>(emptyList()) }
    var next by remember(url) { mutableStateOf<Page?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var loadingMore by remember(url) { mutableStateOf(false) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var attempt by remember(url) { mutableStateOf(0) }
    var descExpanded by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, attempt) {
        loading = true
        error = null
        try {
            val p = repo.channel(url)
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

    val channelUrl = page?.url ?: url
    val subscribed by remember(channelUrl) { repo.isSubscribed(channelUrl) }.collectAsStateWithLifecycle(false)

    Column(Modifier.fillMaxSize()) {
        GatraTopBar(
            leading = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = G.Text) }
            }
        )
        when {
            loading && page == null -> LoadingBox()
            error != null && page == null -> ErrorBox(error ?: "") { attempt++ }
            page != null -> {
                val p = page!!
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                InitialAvatar(p.name, 72.dp, imageUrl = p.avatar)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (p.subscribers >= 0) {
                                        Text("${compactCount(p.subscribers)} pelanggan", color = G.Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            if (subscribed) repo.unsubscribe(channelUrl)
                                            else repo.subscribe(channelUrl, p.name, p.avatar)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = if (subscribed)
                                    ButtonDefaults.buttonColors(containerColor = G.SurfaceHigh, contentColor = G.Gold)
                                else
                                    ButtonDefaults.buttonColors(containerColor = G.Gold, contentColor = G.OnGold)
                            ) { Text(if (subscribed) "Berlangganan" else "Langganan") }

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
                    item { SectionTitle("Video") }
                    if (videos.isEmpty()) {
                        item { EmptyState("Belum ada video", "Channel ini belum punya video yang bisa ditampilkan.") }
                    }
                    items(videos) { v -> VideoCard(v, { onVideo(v.url) }) }
                    if (next != null && p.tab != null) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (loadingMore) LoadingBox() else Button(
                                    onClick = {
                                        val n = next
                                        val tab = p.tab
                                        if (n != null && tab != null) {
                                            scope.launch {
                                                loadingMore = true
                                                try {
                                                    val more = repo.moreChannel(tab, n, p.name, channelUrl)
                                                    videos = videos + more.videos
                                                    next = more.next
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    error = friendlyError(e)
                                                }
                                                loadingMore = false
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = G.SurfaceHigh, contentColor = G.Gold)
                                ) { Text("Muat lebih banyak") }
                            }
                        }
                    }
                }
            }
        }
    }
}
