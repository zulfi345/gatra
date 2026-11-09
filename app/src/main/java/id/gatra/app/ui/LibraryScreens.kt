package id.gatra.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.gatra.app.GatraApp
import id.gatra.app.data.DL_DONE
import id.gatra.app.data.DL_RUNNING
import id.gatra.app.data.VideoItem
import id.gatra.app.data.toVideo
import id.gatra.app.ui.theme.G
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
private fun appOf(): GatraApp = LocalContext.current.applicationContext as GatraApp

@Composable
private fun SubTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    GatraTopBar(
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = G.Text)
                }
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        actions = actions
    )
}

/** Pilihan bahasa audio utama: kode bahasa dan namanya ("" = audio asli). */
private val AUDIO_LANGS = listOf(
    "" to "Asli (bawaan video)",
    "id" to "Indonesia",
    "en" to "Inggris",
    "ja" to "Jepang",
    "ko" to "Korea",
    "ar" to "Arab",
    "hi" to "Hindi",
    "es" to "Spanyol",
    "fr" to "Prancis",
    "de" to "Jerman",
    "pt" to "Portugis",
    "ru" to "Rusia",
    "zh" to "Mandarin",
    "ms" to "Melayu"
)

private fun mb(bytes: Long): String = String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)

// ---------------------------------------------------------------- Koleksi (tab)

@Composable
fun LibraryScreen(
    onWatchLater: () -> Unit,
    onPlaylists: () -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit
) {
    val app = appOf()
    val later by app.library.watchLater.collectAsStateWithLifecycle(emptyList())
    val lists by app.library.playlists.collectAsStateWithLifecycle(emptyList())
    val dls by app.library.downloads.collectAsStateWithLifecycle(emptyList())

    Column(Modifier.fillMaxSize()) {
        GatraTopBar(leading = { Text("Koleksi", style = MaterialTheme.typography.titleLarge) })
        LibRow("Tonton nanti", "${later.size} video", onWatchLater)
        LibRow("Playlist", "${lists.size} playlist", onPlaylists)
        LibRow("Unduhan", "${dls.count { it.status == DL_DONE }} video tersimpan di HP", onDownloads)
        LibRow("Pengaturan", "Putar otomatis, hemat data, SponsorBlock, cache", onSettings)
    }
}

@Composable
private fun LibRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = G.Muted)
    }
    HorizontalDivider(color = G.Outline)
}

// ---------------------------------------------------------------- Tonton nanti

@Composable
fun WatchLaterScreen(onBack: () -> Unit, onVideo: (String) -> Unit, onPlayAll: (List<VideoItem>) -> Unit) {
    val app = appOf()
    val scope = rememberCoroutineScope()
    val items by app.library.watchLater.collectAsStateWithLifecycle(emptyList())

    Column(Modifier.fillMaxSize()) {
        SubTopBar("Tonton nanti", onBack)
        if (items.isEmpty()) {
            EmptyState("Belum ada video", "Ketuk “Tonton nanti” di bawah video untuk menyimpannya di sini.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Button(
                        onClick = { onPlayAll(items.map { it.toVideo() }) },
                        colors = ButtonDefaults.buttonColors(containerColor = G.Gold, contentColor = G.OnGold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text("Putar semua") }
                }
                items(items, key = { it.url }) { e ->
                    CompactVideoRow(
                        e.toVideo(),
                        onClick = { onVideo(e.url) },
                        trailing = {
                            IconButton(onClick = { scope.launch { app.library.removeWatchLater(e.url) } }) {
                                Icon(Icons.Default.Delete, "Hapus", tint = G.Muted)
                            }
                        }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Playlist

@Composable
fun PlaylistsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val app = appOf()
    val scope = rememberCoroutineScope()
    val lists by app.library.playlists.collectAsStateWithLifecycle(emptyList())
    val counts by app.library.playlistCounts.collectAsStateWithLifecycle(emptyMap())
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        SubTopBar("Playlist", onBack) {
            IconButton(onClick = { name = ""; creating = true }) {
                Icon(Icons.Default.Add, "Playlist baru", tint = G.Text)
            }
        }
        if (lists.isEmpty()) {
            EmptyState("Belum ada playlist", "Ketuk + di atas untuk membuat playlist, atau pakai tombol “Playlist” di bawah video.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(lists, key = { it.id }) { p ->
                    LibRow(p.name, "${counts[p.id] ?: 0} video") { onOpen(p.id) }
                }
            }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            containerColor = G.SurfaceHigh,
            title = { Text("Playlist baru") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    singleLine = true,
                    label = { Text("Nama playlist") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val n = name
                        creating = false
                        scope.launch { app.library.createPlaylist(n) }
                    }
                ) { Text("Buat", color = G.Gold) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Batal", color = G.Muted) } }
        )
    }
}

@Composable
fun PlaylistDetailScreen(id: Long, onBack: () -> Unit, onVideo: (String) -> Unit, onPlayAll: (List<VideoItem>) -> Unit) {
    val app = appOf()
    val scope = rememberCoroutineScope()
    val lists by app.library.playlists.collectAsStateWithLifecycle(emptyList())
    val items by remember(id) { app.library.playlistItems(id) }.collectAsStateWithLifecycle(emptyList())
    var confirm by remember { mutableStateOf(false) }
    val title = lists.firstOrNull { it.id == id }?.name ?: "Playlist"

    Column(Modifier.fillMaxSize()) {
        SubTopBar(title, onBack) {
            IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Hapus playlist", tint = G.Text) }
        }
        if (items.isEmpty()) {
            EmptyState("Playlist kosong", "Tambahkan video lewat tombol “Playlist” di bawah video.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Button(
                        onClick = { onPlayAll(items.map { it.toVideo() }) },
                        colors = ButtonDefaults.buttonColors(containerColor = G.Gold, contentColor = G.OnGold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text("Putar semua") }
                }
                items(items, key = { it.url }) { e ->
                    CompactVideoRow(
                        e.toVideo(),
                        onClick = { onVideo(e.url) },
                        trailing = {
                            IconButton(onClick = { scope.launch { app.library.removeFromPlaylist(id, e.url) } }) {
                                Icon(Icons.Default.Delete, "Hapus dari playlist", tint = G.Muted)
                            }
                        }
                    )
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = G.SurfaceHigh,
            title = { Text("Hapus playlist?") },
            text = { Text("Playlist “$title” dihapus. Videonya sendiri tidak terhapus dari YouTube.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        app.library.deletePlaylist(id)
                        onBack()
                    }
                }) { Text("Hapus", color = G.Gold) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Batal", color = G.Muted) } }
        )
    }
}

// ---------------------------------------------------------------- Unduhan

@Composable
fun DownloadsScreen(onBack: () -> Unit, onVideo: (String) -> Unit) {
    val app = appOf()
    val ctx = LocalContext.current
    val items by app.library.downloads.collectAsStateWithLifecycle(emptyList())

    Column(Modifier.fillMaxSize()) {
        SubTopBar("Unduhan", onBack)
        if (items.isEmpty()) {
            EmptyState("Belum ada unduhan", "Ketuk “Unduh” di bawah video untuk menyimpannya dan menontonnya tanpa internet.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.url }) { d ->
                    val running = d.status == DL_RUNNING && app.downloads.isRunning(d.url)
                    val done = d.status == DL_DONE
                    val frac = if (d.totalBytes > 0) (d.doneBytes.toFloat() / d.totalBytes).coerceIn(0f, 1f) else 0f
                    val video = VideoItem(d.url, d.title, d.channel, null, d.thumbnail, d.duration, -1L, null)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = done) { onVideo(d.url) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Thumb(video.thumb, video.durationSec, false, Modifier.width(130.dp).aspectRatio(16f / 9f), corner = 10.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            val statusText = when {
                                done -> (if (d.quality > 0) "${d.quality}p" else "Audio") + " • " + mb(d.totalBytes)
                                running -> "Mengunduh… ${(frac * 100).toInt()}%"
                                else -> "Terhenti / gagal"
                            }
                            Text(statusText, style = MaterialTheme.typography.bodySmall, color = G.Muted)
                            if (running) {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { frac },
                                    color = G.Gold,
                                    trackColor = G.Outline,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (!done && !running) {
                                TextButton(onClick = {
                                    app.downloads.retry(d) { msg ->
                                        if (msg != null) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }) { Text("Coba lagi", color = G.Gold) }
                            }
                        }
                        IconButton(onClick = { app.downloads.delete(d) }) {
                            Icon(Icons.Default.Delete, "Hapus unduhan", tint = G.Muted)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Pengaturan

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = appOf()
    val st = app.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoplay by remember { mutableStateOf(st.autoplay) }
    var sponsor by remember { mutableStateOf(st.sponsorBlock) }
    var saver by remember { mutableStateOf(st.dataSaver) }
    var wifiOnly by remember { mutableStateOf(st.wifiOnlyDownload) }
    var cacheBytes by remember { mutableLongStateOf(app.playback.cacheBytes()) }
    var confirmHistory by remember { mutableStateOf(false) }
    var audioLang by remember { mutableStateOf(st.preferredAudioLang) }
    var pickingAudio by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        SubTopBar("Pengaturan", onBack)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SwitchRow("Putar otomatis", "Setelah video selesai, putar video berikutnya.", autoplay) {
                    autoplay = it; st.autoplay = it
                }
                SwitchRow("Hemat data di jaringan seluler", "Kualitas otomatis dibatasi maksimal 360p saat memakai kuota.", saver) {
                    saver = it; st.dataSaver = it
                }
                SwitchRow("Unduh hanya lewat Wi-Fi", "Cegah unduhan memakai kuota seluler.", wifiOnly) {
                    wifiOnly = it; st.wifiOnlyDownload = it
                }
                SwitchRow(
                    "Lewati bagian sponsor (SponsorBlock)",
                    "Memakai layanan komunitas sponsor.ajay.app. Awalan kode video dikirim ke layanan itu; kamu bisa mematikannya kapan saja.",
                    sponsor
                ) { sponsor = it; st.sponsorBlock = it }
                HorizontalDivider(color = G.Outline)
                LibRow(
                    "Bahasa audio utama",
                    (AUDIO_LANGS.firstOrNull { it.first == audioLang }?.second ?: "Asli (bawaan video)") +
                        " • dipakai otomatis di video yang punya dubbing"
                ) { pickingAudio = true }
                LibRow("Tampilkan lagi video yang disembunyikan", "Dari menu “Tidak tertarik” dan “Jangan rekomendasikan channel”") {
                    st.clearHidden()
                    android.widget.Toast.makeText(ctx, "Semua video dan channel ditampilkan lagi.", android.widget.Toast.LENGTH_SHORT).show()
                }
                LibRow("Atur ulang preferensi rekomendasi", "Hapus minat yang dipelajari dari tontonanmu; Beranda mulai dari nol") {
                    scope.launch {
                        try {
                            app.repo.resetSignals()
                            android.widget.Toast.makeText(ctx, "Preferensi rekomendasi diatur ulang.", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }
                LibRow("Hapus cache video", "Terpakai ${mb(cacheBytes)}") {
                    app.playback.clearCache()
                    cacheBytes = app.playback.cacheBytes()
                }
                LibRow("Hapus riwayat tontonan", "Menghapus semua daftar di tab Riwayat") { confirmHistory = true }
                LibRow("Tentang Gatra", "Versi 1.0.0 • Untuk Indonesia Maju") {}
            }
        }
    }

    if (pickingAudio) {
        AlertDialog(
            onDismissRequest = { pickingAudio = false },
            containerColor = G.SurfaceHigh,
            title = { Text("Bahasa audio utama") },
            text = {
                Column {
                    Text(
                        "Dipakai otomatis di setiap video yang punya trek audio bahasa ini (dubbing YouTube). " +
                            "Kalau videonya tidak punya, dipakai audio asli. Memilih bahasa lain di menu pemutar juga mengubah pilihan ini.",
                        color = G.Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(AUDIO_LANGS) { (code, name) ->
                            Text(
                                name,
                                color = if (code == audioLang) G.Gold else G.Text,
                                fontWeight = if (code == audioLang) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        audioLang = code
                                        st.preferredAudioLang = code
                                        pickingAudio = false
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingAudio = false }) { Text("Tutup", color = G.Gold) } }
        )
    }

    if (confirmHistory) {
        AlertDialog(
            onDismissRequest = { confirmHistory = false },
            containerColor = G.SurfaceHigh,
            title = { Text("Hapus semua riwayat?") },
            text = { Text("Daftar video yang pernah kamu tonton akan dihapus.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmHistory = false
                    scope.launch {
                        try {
                            app.repo.clearHistory()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }) { Text("Hapus", color = G.Gold) }
            },
            dismissButton = { TextButton(onClick = { confirmHistory = false }) { Text("Batal", color = G.Muted) } }
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = G.Muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = G.Gold, checkedThumbColor = G.OnGold)
        )
    }
}
