package id.gatra.app.ui

import id.gatra.app.ui.theme.Wordmark
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import id.gatra.app.GatraApp
import id.gatra.app.data.VideoItem
import id.gatra.app.player.ShortsController
import id.gatra.app.player.ShortsState
import id.gatra.app.ui.theme.G

/** Tab Shorts: satu video vertikal pendek per layar, digeser atas-bawah. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortsScreen(onChannel: (String) -> Unit, onOpenVideo: (String) -> Unit) {
    val ctx = LocalContext.current
    val shorts = (ctx.applicationContext as GatraApp).shorts
    val items by shorts.items.collectAsStateWithLifecycle()
    val loading by shorts.loading.collectAsStateWithLifecycle()
    val error by shorts.error.collectAsStateWithLifecycle()
    val st by shorts.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        shorts.ensureLoaded()
        shorts.resume()
    }

    // Jeda saat pindah tab atau aplikasi ke latar.
    DisposableEffect(Unit) {
        val owner = ctx.findActivity() as? LifecycleOwner
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) shorts.pause() }
        owner?.lifecycle?.addObserver(observer)
        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            shorts.pause()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (items.isEmpty()) {
            val err = error
            when {
                loading -> LoadingBox()
                err != null -> ErrorBox(err, shorts::refresh)
                else -> EmptyState("Belum ada Shorts", "Tarik tombol muat ulang di kanan atas.")
            }
        } else {
            val pager = rememberPagerState(pageCount = { items.size })
            LaunchedEffect(pager.settledPage, items.size) {
                items.getOrNull(pager.settledPage)?.let { shorts.play(it) }
                shorts.prefetch(items.getOrNull(pager.settledPage + 1))
                if (pager.settledPage >= items.size - 3) shorts.loadMore()
            }
            VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val v = items[page]
                ShortPage(
                    v = v,
                    isCurrent = pager.currentPage == page,
                    st = st,
                    shorts = shorts,
                    onChannel = onChannel,
                    onOpenVideo = onOpenVideo
                )
            }
        }

        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Shorts", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = shorts::refresh) { Icon(Icons.Default.Refresh, "Muat ulang Shorts", tint = Color.White) }
        }
    }
}

@Composable
private fun ShortPage(
    v: VideoItem,
    isCurrent: Boolean,
    st: ShortsState,
    shorts: ShortsController,
    onChannel: (String) -> Unit,
    onOpenVideo: (String) -> Unit
) {
    val mine = isCurrent && st.url == v.url
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (mine) shorts.togglePlay()
            }
    ) {
        // Gambar sampul tampil sampai gambar pertama video muncul.
        if (!(mine && st.firstFrame)) {
            Thumb(v.thumb, 0L, false, Modifier.fillMaxSize(), corner = 0.dp)
        }
        if (isCurrent) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        keepScreenOn = true
                        player = shorts.player
                        visibility = View.VISIBLE
                    }
                },
                update = { it.player = shorts.player },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (mine && st.loading) {
            CircularProgressIndicator(color = G.Gold, modifier = Modifier.align(Alignment.Center))
        }
        if (mine && st.error) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Shorts ini gagal diputar.", color = Color.White)
                TextButton(onClick = shorts::retry) { Text("Coba lagi", color = G.Gold) }
            }
        }
        if (mine && !st.playing && !st.loading && !st.error && st.firstFrame) {
            Icon(
                Icons.Default.PlayArrow, "Putar",
                tint = Color(0xCCFFFFFF),
                modifier = Modifier.align(Alignment.Center).size(72.dp)
            )
        }

        Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 8.dp)) { Wordmark() }

        // Bayangan bawah supaya teks terbaca.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(190.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.8f)
                .padding(start = 16.dp, bottom = 20.dp)
        ) {
            Text(
                v.channel,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = v.channelUrl != null) { v.channelUrl?.let(onChannel) }
            )
            Spacer(Modifier.height(4.dp))
            Text(v.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            VideoMenuButton(v)
            IconButton(onClick = { shorts.pause(); onOpenVideo(v.url) }) {
                Icon(Icons.Default.PlayArrow, "Buka di pemutar penuh", tint = Color.White)
            }
        }
    }
}
