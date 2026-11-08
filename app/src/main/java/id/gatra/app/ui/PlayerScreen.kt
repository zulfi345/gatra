package id.gatra.app.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import id.gatra.app.data.Repo
import id.gatra.app.data.VideoItem
import id.gatra.app.data.bestUrl
import id.gatra.app.data.compactCount
import id.gatra.app.data.toVideo
import id.gatra.app.player.PlaybackController
import id.gatra.app.player.PlayerState
import id.gatra.app.ui.theme.G
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem

@Composable
fun PlayerScreen(
    playback: PlaybackController,
    repo: Repo,
    onChannel: (String) -> Unit
) {
    val st by playback.state.collectAsStateWithLifecycle()
    val inPip = LocalInPip.current
    val activity = LocalContext.current.findActivity()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var manualFullscreen by remember { mutableStateOf(false) }
    var forcePortrait by remember { mutableStateOf(false) }
    val fullscreen = landscape || manualFullscreen
    val immersive = (fullscreen || inPip)

    val toggleFullscreen = rememberUpdatedState<() -> Unit> {
        if (fullscreen) {
            manualFullscreen = false
            forcePortrait = true
        } else {
            manualFullscreen = true
        }
    }

    // Atur orientasi layar sesuai tombol layar penuh.
    LaunchedEffect(manualFullscreen, forcePortrait) {
        activity.requestedOrientation = when {
            manualFullscreen -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            forcePortrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (forcePortrait && !manualFullscreen) {
            delay(1200)
            forcePortrait = false
        }
    }

    // Sembunyikan bilah sistem saat layar penuh.
    LaunchedEffect(immersive) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (immersive) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = fullscreen && !inPip) {
        manualFullscreen = false
        forcePortrait = true
    }

    Column(Modifier.fillMaxSize().background(if (immersive) Color.Black else G.Bg)) {
        val videoModifier = if (immersive) Modifier.fillMaxSize()
        else Modifier.fillMaxWidth().aspectRatio(16f / 9f)

        // Satu PlayerView saja yang dipakai di semua mode agar layar video tidak dibuat ulang.
        Box(videoModifier.background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = playback.player
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setControllerShowTimeoutMs(3000)
                        setFullscreenButtonClickListener { toggleFullscreen.value() }
                    }
                },
                update = { view ->
                    view.useController = !inPip
                    view.player = playback.player
                }
            )

            if (st.loading) {
                CircularProgressIndicator(color = G.Gold, modifier = Modifier.align(Alignment.Center))
            }
            val err = st.error
            if (err != null) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(err, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = playback::retry,
                        colors = ButtonDefaults.buttonColors(containerColor = G.Gold, contentColor = G.OnGold)
                    ) { Text("Coba lagi") }
                }
            }

            if (!inPip) {
                if (!fullscreen) {
                    IconButton(
                        onClick = playback::minimize,
                        modifier = Modifier.align(Alignment.TopStart)
                    ) { Icon(Icons.Default.KeyboardArrowDown, "Kecilkan", tint = Color.White) }
                }
                if (st.qualities.size > 1) {
                    QualityChip(st, playback, Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
        }

        if (!immersive) {
            PlayerDetails(st, playback, repo, onChannel, Modifier.weight(1f))
        }
    }
}

@Composable
private fun QualityChip(st: PlayerState, playback: PlaybackController, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Text(
            "${st.selectedHeight}p",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .background(Color(0x99000000), RoundedCornerShape(16.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            st.qualities.sortedByDescending { it.height }.forEach { q ->
                DropdownMenuItem(
                    text = {
                        Text(
                            q.label,
                            color = if (q.height == st.selectedHeight) G.Gold else G.Text,
                            fontWeight = if (q.height == st.selectedHeight) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        open = false
                        playback.setQuality(q)
                    }
                )
            }
        }
    }
}

private fun plainDescription(d: Description?): String {
    if (d == null) return ""
    val raw = d.content ?: return ""
    return if (d.type == Description.HTML) {
        HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    } else raw.trim()
}

@Composable
private fun PlayerDetails(
    st: PlayerState,
    playback: PlaybackController,
    repo: Repo,
    onChannel: (String) -> Unit,
    modifier: Modifier
) {
    val info = st.info
    if (info == null) {
        Box(modifier.fillMaxWidth())
        return
    }
    val scope = rememberCoroutineScope()
    val uploaderUrl = info.uploaderUrl.orEmpty()
    val subscribed by remember(uploaderUrl) {
        if (uploaderUrl.isBlank()) flowOf(false) else repo.isSubscribed(uploaderUrl)
    }.collectAsStateWithLifecycle(false)

    val related: List<VideoItem> = remember(info) {
        info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toVideo() }
    }
    var descExpanded by remember(info) { mutableStateOf(false) }
    val description = remember(info) { plainDescription(info.description) }

    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(info.name ?: "", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                val meta = buildList {
                    if (st.isLive) add("Siaran langsung")
                    else if (info.viewCount >= 0) add("${compactCount(info.viewCount)} x ditonton")
                    info.textualUploadDate?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(" • ")
                if (meta.isNotEmpty()) Text(meta, color = G.Muted, style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clickable(enabled = uploaderUrl.isNotBlank()) { onChannel(uploaderUrl) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InitialAvatar(info.uploaderName ?: "", 40.dp, imageUrl = info.uploaderAvatars.bestUrl())
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(info.uploaderName ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            if (info.uploaderSubscriberCount >= 0) {
                                Text(
                                    "${compactCount(info.uploaderSubscriberCount)} pelanggan",
                                    color = G.Muted, style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    if (uploaderUrl.isNotBlank()) {
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        if (subscribed) repo.unsubscribe(uploaderUrl)
                                        else repo.subscribe(uploaderUrl, info.uploaderName ?: "", info.uploaderAvatars.bestUrl())
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                    }
                                }
                            },
                            colors = if (subscribed)
                                ButtonDefaults.buttonColors(containerColor = G.SurfaceHigh, contentColor = G.Gold)
                            else
                                ButtonDefaults.buttonColors(containerColor = G.Gold, contentColor = G.OnGold)
                        ) { Text(if (subscribed) "Berlangganan" else "Langganan") }
                    }
                }

                if (description.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = G.Text,
                        maxLines = if (descExpanded) Int.MAX_VALUE else 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(G.SurfaceHigh, RoundedCornerShape(12.dp))
                            .clickable { descExpanded = !descExpanded }
                            .padding(12.dp)
                    )
                }
            }
        }
        if (related.isNotEmpty()) {
            item { SectionTitle("Video terkait") }
            items(related) { v -> CompactVideoRow(v, onClick = { playback.open(v.url) }) }
        }
    }
}
