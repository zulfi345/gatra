package id.gatra.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import id.gatra.app.data.VideoItem
import id.gatra.app.data.friendlyError
import id.gatra.app.data.toVideoItem
import id.gatra.app.player.QualityOption
import id.gatra.app.player.buildAudioTracks
import id.gatra.app.player.buildQualityOptions
import id.gatra.app.player.clenOfUrl
import id.gatra.app.player.pickAudio
import id.gatra.app.ui.theme.G
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo

private fun fmtSize(bytes: Long): String =
    if (bytes >= 1_073_741_824L) {
        String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    } else {
        "${(bytes / 1_048_576L).coerceAtLeast(1L)} MB"
    }

/** Pilih resolusi dan bahasa audio (dubbing), lihat perkiraan ukuran, lalu mulai mengunduh. */
@Composable
fun DownloadDialog(info: StreamInfo, qualities: List<QualityOption>, app: GatraApp, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val tracks = remember(info) { buildAudioTracks(info) }

    // Pilihan awal bahasa audio: yang sedang diputar untuk video ini, lalu bahasa audio utama, lalu trek asli.
    var trackId by remember(info) {
        val playing = app.playback.state.value
        val current = if (playing.info?.url == info.url) playing.audioTrackSel else null
        val prefLang = app.settings.preferredAudioLang
        mutableStateOf(
            tracks.firstOrNull { it.id == current }?.id
                ?: (if (prefLang.isNotBlank()) tracks.firstOrNull { it.lang == prefLang }?.id else null)
                ?: tracks.firstOrNull { it.original }?.id
                ?: tracks.firstOrNull()?.id
        )
    }
    val audioStream = pickAudio(info, trackId)
    val audioBytes = audioStream?.content?.let { clenOfUrl(it) } ?: 0L

    fun begin(option: QualityOption?, audioOnly: Boolean) {
        val msg = app.downloads.start(info, option, audioOnly, trackId)
        if (msg == null) app.repo.noteAction(info.toVideoItem(), 3.0)
        Toast.makeText(ctx, msg ?: "Unduhan dimulai.", Toast.LENGTH_LONG).show()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = G.SurfaceHigh,
        title = { Text("Unduh video") },
        text = {
            Column {
                Text(
                    "Pilih resolusi lalu ketuk untuk mulai. Ukuran adalah perkiraan. Video yang diunduh bisa ditonton tanpa internet dari menu Koleksi › Unduhan.",
                    color = G.Muted,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (tracks.isNotEmpty()) {
                        item {
                            Text(
                                "Bahasa audio (dubbing)",
                                color = G.Muted,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        items(tracks) { t ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { trackId = t.id }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = trackId == t.id, onClick = { trackId = t.id })
                                Text(t.label)
                            }
                        }
                        item { Spacer(Modifier.height(10.dp)) }
                    }
                    item {
                        Text(
                            "Resolusi",
                            color = G.Muted,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    items(qualities.sortedByDescending { it.height }) { q ->
                        val videoUrl: String? = q.video?.content
                        val total = (videoUrl?.let { clenOfUrl(it) } ?: 0L) + (if (q.muxed) 0L else audioBytes)
                        Text(
                            if (total > 0L) "${q.label}  •  sekitar ${fmtSize(total)}" else q.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { begin(q, false) }
                                .padding(vertical = 12.dp)
                        )
                    }
                    item {
                        Text(
                            if (audioBytes > 0L) "Audio saja  •  sekitar ${fmtSize(audioBytes)}" else "Audio saja (paling kecil)",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { begin(null, true) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Batal", color = G.Gold) } }
    )
}

/** Unduh dari daftar video: ambil data video dulu, lalu pilih kualitas. */
@Composable
fun DownloadByUrlDialog(video: VideoItem, app: GatraApp, onDismiss: () -> Unit) {
    var info by remember { mutableStateOf<StreamInfo?>(null) }
    var options by remember { mutableStateOf<List<QualityOption>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(video.url) {
        try {
            val i = withContext(Dispatchers.IO) { StreamInfo.getInfo(ServiceList.YouTube, video.url) }
            options = buildQualityOptions(i, false)
            info = i
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
        }
    }

    val ready = info
    if (ready != null) {
        DownloadDialog(ready, options, app, onDismiss)
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = G.SurfaceHigh,
            title = { Text("Unduh video") },
            text = {
                val err = error
                if (err == null) CircularProgressIndicator(color = G.Gold) else Text(err, color = G.Muted)
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Batal", color = G.Gold) } }
        )
    }
}

/** Tambahkan video ke playlist yang ada, atau buat playlist baru. */
@Composable
fun PlaylistDialog(video: VideoItem, app: GatraApp, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lists by app.library.playlists.collectAsStateWithLifecycle(emptyList())
    var name by remember { mutableStateOf("") }

    fun addTo(id: Long, label: String) {
        scope.launch {
            try {
                app.library.addToPlaylist(id, video)
                app.repo.noteAction(video, 3.0)
                Toast.makeText(ctx, "Ditambahkan ke “$label”.", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(ctx, "Gagal menambahkan.", Toast.LENGTH_SHORT).show()
            }
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = G.SurfaceHigh,
        title = { Text("Simpan ke playlist") },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(lists) { p ->
                        Text(
                            p.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { addTo(p.id, p.name) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
                if (lists.isEmpty()) {
                    Text("Belum ada playlist. Buat yang pertama di bawah.", color = G.Muted)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    singleLine = true,
                    label = { Text("Playlist baru") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    scope.launch {
                        try {
                            val id = app.library.createPlaylist(name)
                            app.library.addToPlaylist(id, video)
                            Toast.makeText(ctx, "Playlist dibuat.", Toast.LENGTH_SHORT).show()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Gagal membuat playlist.", Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    }
                }
            ) { Text("Buat & simpan", color = G.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal", color = G.Muted) } }
    )
}

private class CommentLine(val author: String, val text: String, val likes: Int, val date: String)

/** Komentar video (halaman pertama, bisa dimuat lagi). */
@Composable
fun CommentsDialog(url: String, onDismiss: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var comments by remember { mutableStateOf<List<CommentLine>>(emptyList()) }
    var holder by remember { mutableStateOf<CommentsInfo?>(null) }
    var next by remember { mutableStateOf<org.schabi.newpipe.extractor.Page?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun toLine(c: CommentsInfoItem) = CommentLine(
        author = c.uploaderName.orEmpty(),
        text = plainDescription(c.commentText),
        likes = c.likeCount,
        date = c.textualUploadDate.orEmpty()
    )

    LaunchedEffect(url) {
        try {
            val ci = withContext(Dispatchers.IO) { CommentsInfo.getInfo(ServiceList.YouTube, url) }
            holder = ci
            next = ci.nextPage
            comments = ci.relatedItems.filterIsInstance<CommentsInfoItem>().map { toLine(it) }
            if (comments.isEmpty()) error = "Komentar dimatikan atau belum ada komentar."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = G.SurfaceHigh,
        title = { Text("Komentar") },
        text = {
            when {
                loading -> CircularProgressIndicator(color = G.Gold)
                error != null && comments.isEmpty() -> Text(error ?: "", color = G.Muted)
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(comments) { c ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(
                                c.author + if (c.date.isNotBlank()) " • ${c.date}" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = G.Muted
                            )
                            Text(c.text, style = MaterialTheme.typography.bodyMedium)
                            if (c.likes > 0) {
                                Text("👍 ${c.likes}", style = MaterialTheme.typography.labelSmall, color = G.Muted)
                            }
                        }
                    }
                    if (next != null) {
                        item {
                            TextButton(
                                enabled = !loadingMore,
                                onClick = {
                                    val h = holder
                                    val pg = next
                                    if (h != null && pg != null) {
                                        loadingMore = true
                                        scope.launch {
                                            try {
                                                val page = withContext(Dispatchers.IO) {
                                                    CommentsInfo.getMoreItems(ServiceList.YouTube, h, pg)
                                                }
                                                comments = comments + page.items.map { toLine(it) }
                                                next = page.nextPage
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                next = null
                                            }
                                            loadingMore = false
                                        }
                                    }
                                }
                            ) { Text(if (loadingMore) "Memuat…" else "Muat lebih banyak", color = G.Gold) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup", color = G.Gold) } }
    )
}
