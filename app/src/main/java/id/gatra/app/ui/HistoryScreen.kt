package id.gatra.app.ui

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import id.gatra.app.GatraApp
import id.gatra.app.data.HistoryEntity
import id.gatra.app.data.VideoItem
import id.gatra.app.data.relativeTime
import id.gatra.app.ui.theme.G
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as GatraApp).repo

    val items: StateFlow<List<HistoryEntity>> =
        repo.history.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun remove(url: String) {
        viewModelScope.launch { repo.removeHistory(url) }
    }

    fun clear() {
        viewModelScope.launch { repo.clearHistory() }
    }
}

@Composable
fun HistoryScreen(onBack: () -> Unit, onVideo: (String) -> Unit, vm: HistoryViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var visible by remember { mutableIntStateOf(40) }
    InfiniteScrollEffect(listState, enabled = visible < items.size) { visible += 40 }

    Column(Modifier.fillMaxSize()) {
        GatraTopBar(
            leading = {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = G.Text)
                    }
                    Text("Riwayat Tontonan", style = MaterialTheme.typography.titleLarge)
                }
            },
            actions = {
                if (items.isNotEmpty()) {
                    Row(
                        Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .background(G.SurfaceHigh)
                            .clickable { confirm = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Delete, "Hapus semua riwayat", tint = G.Muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Hapus", color = G.Muted, fontSize = 13.sp)
                    }
                }
            }
        )
        if (items.isEmpty()) {
            EmptyState("Riwayat masih kosong", "Video yang kamu tonton akan muncul di sini. Riwayat hanya tersimpan di HP ini.")
        } else {
            val shown = items.take(visible)
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                var lastLabel = ""
                shown.forEach { h ->
                    val label = dayLabel(h.watchedAt)
                    if (label != lastLabel) {
                        lastLabel = label
                        item(key = "hdr-$label") {
                            Text(
                                label, color = G.Muted, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp)
                            )
                        }
                    }
                    item(key = h.url) {
                        val v = VideoItem(
                            url = h.url, title = h.title, channel = h.channel,
                            channelUrl = h.channelUrl.ifBlank { null }, thumb = h.thumbnail,
                            durationSec = h.duration, views = -1L, date = relativeTime(h.watchedAt)
                        )
                        CompactVideoRow(v, onClick = { onVideo(h.url) }, trailing = {
                            IconButton(onClick = { vm.remove(h.url) }) {
                                Icon(Icons.Default.Close, "Hapus dari riwayat", tint = G.Muted)
                            }
                        })
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = G.SurfaceHigh,
            title = { Text("Hapus semua riwayat?") },
            text = { Text("Daftar video yang pernah kamu tonton akan dihapus dari HP ini.") },
            confirmButton = {
                TextButton(onClick = { vm.clear(); confirm = false }) { Text("Hapus", color = G.Gold) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Batal", color = G.Text) }
            }
        )
    }
}

private fun dayLabel(ms: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val diff = java.time.temporal.ChronoUnit.DAYS.between(day, java.time.LocalDate.now(zone))
    return when {
        diff <= 0L -> "Hari ini"
        diff == 1L -> "Kemarin"
        else -> java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy").format(day)
    }
}
