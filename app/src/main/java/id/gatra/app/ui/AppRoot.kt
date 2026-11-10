package id.gatra.app.ui

import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import id.gatra.app.R
import id.gatra.app.data.Repo
import id.gatra.app.data.bestUrl
import id.gatra.app.player.PlaybackController
import id.gatra.app.ui.theme.G

private const val HOME = "home"
private const val SUBS = "subs"
private const val HISTORY = "history"
private const val SEARCH = "search"
private const val LIBRARY = "library"
private const val SHORTS = "shorts"
private const val WATCH_LATER = "watch_later"
private const val PLAYLISTS = "playlists"
private const val PLAYLIST = "playlist/{id}"
private const val DOWNLOADS = "downloads"
private const val SETTINGS = "settings"
private const val CHANNEL = "channel?url={url}"
private const val YT_PLAYLIST = "ytplaylist?url={url}"

@Composable
fun GatraRoot(playback: PlaybackController, repo: Repo) {
    val nav = rememberNavController()
    val player by playback.state.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showTabs = route == HOME || route == SUBS || route == LIBRARY || route == SHORTS

    val openVideo: (String) -> Unit = { playback.open(it) }
    val openChannel: (String) -> Unit = { url ->
        playback.minimize()
        nav.navigate("channel?url=${Uri.encode(url)}")
    }

    val openYtPlaylist: (String) -> Unit = { url ->
        nav.navigate("ytplaylist?url=${Uri.encode(url)}")
    }

    BackHandler(enabled = player.active && player.expanded) { playback.minimize() }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = G.Bg,
            bottomBar = {
                Column {
                    if (player.active && !player.expanded) MiniPlayer(playback)
                    if (showTabs) {
                        NavigationBar(containerColor = Color(0xFF141720)) {
                            val colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = G.Cyan,
                                selectedTextColor = G.Cyan,
                                indicatorColor = G.SurfaceHigh,
                                unselectedIconColor = G.Muted,
                                unselectedTextColor = G.Muted
                            )
                            TabItem("Beranda", route == HOME, colors, rememberVectorPainter(Icons.Default.Home)) {
                                goTab(nav, HOME)
                                HomeEvents.request()
                            }
                            TabItem("Shorts", route == SHORTS, colors, rememberVectorPainter(Icons.Default.PlayArrow)) { goTab(nav, SHORTS) }
                            TabItem("Langganan", route == SUBS, colors, painterResource(R.drawable.ic_subs)) { goTab(nav, SUBS) }
                            TabItem("Koleksi", route == LIBRARY, colors, rememberVectorPainter(Icons.Default.Favorite)) { goTab(nav, LIBRARY) }
                        }
                    }
                }
            }
        ) { padding ->
            NavHost(nav, startDestination = HOME, modifier = Modifier.padding(padding)) {
                composable(HOME) {
                    HomeScreen(onVideo = openVideo, onChannel = openChannel, onSearch = { nav.navigate(SEARCH) })
                }
                composable(SHORTS) { ShortsScreen(onChannel = openChannel, onOpenVideo = openVideo) }
                composable(SUBS) { SubsScreen(onVideo = openVideo, onChannel = openChannel) }
                composable(HISTORY) { HistoryScreen(onBack = { nav.popBackStack() }, onVideo = openVideo) }
                composable(LIBRARY) {
                    LibraryScreen(
                        onWatchLater = { nav.navigate(WATCH_LATER) },
                        onPlaylists = { nav.navigate(PLAYLISTS) },
                        onDownloads = { nav.navigate(DOWNLOADS) },
                        onSettings = { nav.navigate(SETTINGS) },
                        onHistory = { nav.navigate(HISTORY) },
                        onVideo = openVideo
                    )
                }
                composable(WATCH_LATER) {
                    WatchLaterScreen(onBack = { nav.popBackStack() }, onVideo = openVideo, onPlayAll = playback::playAll)
                }
                composable(PLAYLISTS) {
                    PlaylistsScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate("playlist/$it") })
                }
                composable(PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    PlaylistDetailScreen(
                        id = e.arguments?.getLong("id") ?: 0L,
                        onBack = { nav.popBackStack() },
                        onVideo = openVideo,
                        onPlayAll = playback::playAll
                    )
                }
                composable(DOWNLOADS) { DownloadsScreen(onBack = { nav.popBackStack() }, onVideo = openVideo) }
                composable(SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
                composable(SEARCH) {
                    SearchScreen(onBack = { nav.popBackStack() }, onVideo = openVideo, onChannel = openChannel)
                }
                composable(CHANNEL, arguments = listOf(navArgument("url") { type = NavType.StringType })) { e ->
                    val url = Uri.decode(e.arguments?.getString("url").orEmpty())
                    ChannelScreen(
                        url = url,
                        onBack = { nav.popBackStack() },
                        onVideo = openVideo,
                        onPlaylist = openYtPlaylist
                    )
                }
                composable(YT_PLAYLIST, arguments = listOf(navArgument("url") { type = NavType.StringType })) { e ->
                    val url = Uri.decode(e.arguments?.getString("url").orEmpty())
                    YtPlaylistScreen(
                        url = url,
                        onBack = { nav.popBackStack() },
                        onVideo = openVideo,
                        onPlayAll = playback::playAll
                    )
                }
            }
        }

        if (player.active && player.expanded) {
            PlayerScreen(playback = playback, repo = repo, onChannel = openChannel)
        }
    }
}

private fun goTab(nav: androidx.navigation.NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    label: String,
    selected: Boolean,
    colors: androidx.compose.material3.NavigationBarItemColors,
    painter: Painter,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(painter, contentDescription = label) },
        label = { Text(label) },
        colors = colors
    )
}

/** Bilah pemutar kecil di atas menu bawah saat video dikecilkan; audio tetap berjalan. */
@Composable
private fun MiniPlayer(playback: PlaybackController) {
    val st by playback.state.collectAsStateWithLifecycle()
    val info = st.info
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1B1F2A))
            .clickable { playback.expand() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(
            info?.thumbnails?.bestUrl(),
            0L, false,
            Modifier.width(72.dp).aspectRatio(16f / 9f),
            corner = 8.dp
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                info?.name ?: st.offlineTitle ?: if (st.error != null) "Gagal memuat video" else "Memuat video…",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (info != null) {
                Text(
                    info.uploaderName ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = G.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = playback::togglePlay) {
            if (st.isPlaying) Icon(painterResource(R.drawable.ic_pause), "Jeda", tint = G.Text)
            else Icon(Icons.Default.PlayArrow, "Putar", tint = G.Text)
        }
        IconButton(onClick = playback::close) { Icon(Icons.Default.Close, "Tutup pemutar", tint = G.Muted) }
    }
}
