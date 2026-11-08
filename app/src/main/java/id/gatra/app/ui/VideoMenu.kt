package id.gatra.app.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import id.gatra.app.GatraApp
import id.gatra.app.data.VideoItem
import id.gatra.app.ui.theme.G
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Bagikan tautan video lewat aplikasi lain. */
fun shareVideo(context: Context, v: VideoItem) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, v.title)
        putExtra(Intent.EXTRA_TEXT, "${v.title}\n${v.url}")
    }
    try {
        context.startActivity(Intent.createChooser(send, "Bagikan video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(context, "Tidak ada aplikasi untuk berbagi.", Toast.LENGTH_SHORT).show()
    }
}

/** Tombol titik tiga di setiap video: antrean, simpan, unduh, bagikan, sembunyikan. */
@Composable
fun VideoMenuButton(v: VideoItem, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as GatraApp
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.MoreVert, "Opsi video", tint = G.Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuRow("Putar berikutnya dalam antrean") {
                open = false
                app.playback.playNext(v)
            }
            MenuRow("Putar terakhir dalam antrean") {
                open = false
                app.playback.enqueueLast(v)
            }
            MenuRow("Simpan ke Tonton Nanti") {
                open = false
                scope.launch {
                    try {
                        app.library.addWatchLater(v)
                        toast("Disimpan ke Tonton Nanti.")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast("Gagal menyimpan.")
                    }
                }
            }
            MenuRow("Simpan ke playlist") { open = false; showPlaylist = true }
            MenuRow("Unduh") { open = false; showDownload = true }
            MenuRow("Bagikan") { open = false; shareVideo(ctx, v) }
            MenuRow("Tidak tertarik") {
                open = false
                app.settings.hideVideo(v.url)
                toast("Video ini tidak akan muncul di rekomendasi.")
            }
            MenuRow("Jangan rekomendasikan channel") {
                open = false
                app.settings.hideChannel(v.channelUrl ?: v.channel)
                toast("Channel ini disembunyikan dari rekomendasi.")
            }
        }
    }

    if (showPlaylist) PlaylistDialog(v, app) { showPlaylist = false }
    if (showDownload) DownloadByUrlDialog(v, app) { showDownload = false }
}

@Composable
private fun MenuRow(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, color = G.Text) }, onClick = onClick)
}
