package id.gatra.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.GestureDetector
import android.view.View
import android.view.MotionEvent
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import id.gatra.app.GatraApp
import id.gatra.app.data.toVideoItem
import id.gatra.app.data.Repo
import id.gatra.app.data.VideoCursor
import id.gatra.app.data.VideoItem
import id.gatra.app.data.bestUrl
import id.gatra.app.data.compactCount
import id.gatra.app.data.localizeDate
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
    var hud by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    LaunchedEffect(hud) {
        if (hud != null) {
            delay(900)
            hud = null
        }
    }
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

    // Jaga layar tetap menyala selama video diputar / memuat / buffering.
    // Dilepas saat jeda, error, atau pemutar ditutup/dikecilkan.
    val keepAwake = st.error == null && !st.audioOnly && (st.isPlaying || st.buffering || st.loading)
    DisposableEffect(keepAwake) {
        val window = activity.window
        if (keepAwake) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                val lp = activity.window.attributes
                lp.screenBrightness = -1f // kembalikan ke kecerahan sistem
                activity.window.attributes = lp
            } catch (_: Exception) {
            }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = fullscreen && !inPip) {
        manualFullscreen = false
        forcePortrait = true
    }

    // Pemutar berada di luar Scaffold, jadi warna teks harus ditetapkan sendiri (kalau tidak, teks jadi hitam).
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (immersive) Color.Black else G.Bg,
        contentColor = G.Text
    ) {
    Column(Modifier.fillMaxSize()) {
        val videoModifier = if (immersive) Modifier.fillMaxSize()
        else Modifier.fillMaxWidth().aspectRatio(16f / 9f)

        // Satu PlayerView saja yang dipakai di semua mode agar layar video tidak dibuat ulang.
        Box(videoModifier.background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = playback.uiPlayer
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setControllerShowTimeoutMs(3000)
                        // Mundur/maju 10 detik sudah ada lewat ketuk dua kali, jadi tombolnya dihapus.
                        setShowRewindButton(false)
                        setShowFastForwardButton(false)
                        setShowPreviousButton(true)
                        setShowNextButton(true)
                        // Tombol kecilkan dan pengaturan ikut muncul/hilang bersama kontrol pemutar.
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { v -> controlsVisible = (v == View.VISIBLE) }
                        )
                        setFullscreenButtonClickListener { toggleFullscreen.value() }
                        // Gir di bilah kontrol bawah membuka menu pengaturan lengkap milik Gatra.
                        findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
                            showSettings = true
                        }
                        installGestures(this, activity, playback.player) { hud = it }
                    }
                },
                update = { view ->
                    view.useController = !inPip
                    view.player = playback.uiPlayer
                }
            )

            if (st.loading) {
                CircularProgressIndicator(color = G.Gold, modifier = Modifier.align(Alignment.Center))
            }
            if (st.prebuffering && !st.loading && st.error == null) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = G.Gold)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Menyiapkan agar lancar… ${st.aheadSec} dtk siap",
                        color = Color.White, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Tekan putar untuk mulai langsung",
                        color = Color(0xCCFFFFFF), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            val hudText = hud
            if (hudText != null) {
                Hud(hudText, Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
            }
            val noticeText = st.notice
            if (noticeText != null) {
                Hud(noticeText, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
            }
            if (st.audioOnly && !inPip && st.error == null) {
                Hud("Mode audio saja (hemat data)", Modifier.align(Alignment.Center))
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

            if (!inPip && (controlsVisible || st.error != null || st.loading)) {
                if (!fullscreen) {
                    IconButton(
                        onClick = playback::minimize,
                        modifier = Modifier.align(Alignment.TopStart)
                    ) { Icon(Icons.Default.KeyboardArrowDown, "Kecilkan", tint = Color.White) }
                }
                QualityMenu(st, playback, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            PlayerSettingsMenu(
                st = st,
                playback = playback,
                open = showSettings && !inPip,
                onDismiss = { showSettings = false },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 52.dp, bottom = 44.dp)
            )
        }

        if (!immersive) {
            PlayerDetails(st, playback, repo, onChannel, Modifier.weight(1f))
        }
    }
    }
}

internal fun plainDescription(d: Description?): String {
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
    val ctx = LocalContext.current
    val app = ctx.applicationContext as GatraApp
    val current = remember(info) { info.toVideoItem() }
    val later by remember(info.url) { app.library.isWatchLater(info.url ?: "") }.collectAsStateWithLifecycle(false)
    var showDl by remember { mutableStateOf(false) }
    var showPl by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    if (showDl) DownloadDialog(info, st.qualities, app) { showDl = false }
    if (showPl) PlaylistDialog(current, app) { showPl = false }
    if (showComments) CommentsDialog(info.url ?: "") { showComments = false }
    val uploaderUrl = info.uploaderUrl.orEmpty()
    val subscribed by remember(uploaderUrl) {
        if (uploaderUrl.isBlank()) flowOf(false) else repo.isSubscribed(uploaderUrl)
    }.collectAsStateWithLifecycle(false)

    val related: List<VideoItem> = remember(info) {
        info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toVideo() }
    }
    var descExpanded by remember(info) { mutableStateOf(false) }
    val description = remember(info) { plainDescription(info.description) }

    // Video terkait tanpa batas: setelah daftar bawaan habis, lanjut dari pencarian berdasarkan judul.
    val listState = rememberLazyListState()
    val cursor = remember(info) { VideoCursor(repo, (info.name ?: "").take(80)) }
    var extra by remember(info) { mutableStateOf<List<VideoItem>>(emptyList()) }
    var extraLoading by remember(info) { mutableStateOf(false) }
    var extraMore by remember(info) { mutableStateOf(cursor.query.isNotBlank()) }
    InfiniteScrollEffect(listState, enabled = extraMore && !extraLoading && related.isNotEmpty()) {
        scope.launch {
            extraLoading = true
            try {
                val have = HashSet<String>()
                have += related.map { it.url }
                have += extra.map { it.url }
                have += (info.url ?: "")
                val fresh = cursor.next().filter { have.add(it.url) }
                extra = extra + fresh
                if (fresh.isEmpty() && !cursor.hasMore) extraMore = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                extraMore = false
            }
            extraLoading = false
        }
    }

    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        item {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(info.name ?: "", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                val meta = buildList {
                    if (st.isLive) add("Siaran langsung")
                    else if (info.viewCount >= 0) add("${compactCount(info.viewCount)} x ditonton")
                    localizeDate(info.textualUploadDate).takeIf { it.isNotBlank() }?.let { add(it) }
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

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    if (st.hasPrevious) ActionChip("‹ Sebelumnya") { playback.playPrevious() }
                    ActionChip("Berikutnya ›") { playback.skipNext() }
                    ActionChip("Unduh") { showDl = true }
                    ActionChip(if (later) "Tersimpan ✓" else "Tonton nanti", active = later) {
                        scope.launch {
                            try {
                                if (later) app.library.removeWatchLater(current.url) else {
                                    app.library.addWatchLater(current)
                                    app.repo.noteAction(current, 3.0)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                        }
                    }
                    ActionChip("Playlist") { showPl = true }
                    ActionChip("Komentar") { showComments = true }
                    ActionChip("Bagikan") { shareVideo(ctx, current) }
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
        if (st.queue.isNotEmpty()) {
            item { SectionTitle("Antrean berikutnya (${st.queue.size})") }
            itemsIndexed(st.queue) { i, v ->
                CompactVideoRow(
                    v,
                    onClick = { playback.open(v.url) },
                    trailing = {
                        IconButton(onClick = { playback.removeFromQueue(i) }) {
                            Icon(Icons.Default.Close, "Hapus dari antrean", tint = G.Muted)
                        }
                    }
                )
            }
        }
        if (related.isNotEmpty()) {
            item { SectionTitle("Video terkait") }
            items(related) { v ->
                // Tombol titik tiga (antrean, simpan, unduh, bagikan) muncul otomatis di setiap baris.
                CompactVideoRow(v, onClick = { playback.open(v.url) })
            }
            items(extra) { v -> CompactVideoRow(v, onClick = { playback.open(v.url) }) }
            if (extraLoading) item { LoadMoreSpinner() }
        }
    }
}

@Composable
private fun Hud(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(Color(0xB3000000), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun ActionChip(text: String, active: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = if (active) G.OnGold else G.Text,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(if (active) G.Gold else G.SurfaceHigh, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/**
 * Gestur di layar video: ketuk dua kali kiri/kanan = mundur/maju 10 detik;
 * geser atas-bawah di sisi kiri = kecerahan, di sisi kanan = volume.
 * Pendengar sentuh mengembalikan false supaya kontrol bawaan pemutar tetap berfungsi.
 */
@SuppressLint("ClickableViewAccessibility")
private fun installGestures(view: PlayerView, activity: Activity, player: Player, onHud: (String) -> Unit) {
    val audio = view.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var volAcc = 0f
    val detector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val third = view.width / 3f
            return when {
                e.x < third -> {
                    player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                    onHud("− 10 detik")
                    true
                }
                e.x > third * 2 -> {
                    player.seekTo(player.currentPosition + 10_000L)
                    onHud("+ 10 detik")
                    true
                }
                else -> false
            }
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e1 == null || view.height == 0) return false
            if (kotlin.math.abs(distanceY) < kotlin.math.abs(distanceX) * 1.5f) return false
            if (e1.x < view.width / 2f) {
                val lp = activity.window.attributes
                val cur = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                val v = (cur + distanceY / view.height).coerceIn(0.02f, 1f)
                lp.screenBrightness = v
                activity.window.attributes = lp
                onHud("Kecerahan ${(v * 100).toInt()}%")
            } else {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volAcc += distanceY / view.height * max * 1.5f
                val step = volAcc.toInt()
                if (step != 0) {
                    volAcc -= step
                    val nv = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + step).coerceIn(0, max)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                }
                onHud("Volume ${audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max.coerceAtLeast(1)}%")
            }
            return true
        }
    })
    view.setOnTouchListener { _, ev ->
        detector.onTouchEvent(ev)
        false
    }
}
