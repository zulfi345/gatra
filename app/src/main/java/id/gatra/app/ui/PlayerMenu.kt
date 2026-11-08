package id.gatra.app.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.gatra.app.player.PlaybackController
import id.gatra.app.player.PlayerState
import id.gatra.app.ui.theme.G

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun speedLabel(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x".replace('.', ',')

/** Tombol opsi di pojok video: kualitas, kecepatan, subtitle, mode audio, mode lancar, pengatur waktu tidur. */
@Composable
fun PlayerMenu(st: PlayerState, playback: PlaybackController, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) } // 0 utama, 1 kualitas, 2 kecepatan, 3 subtitle, 4 tidur

    val label = when {
        st.audioOnly -> "Audio"
        st.smooth && st.selectedHeight > 0 -> "Lancar · ${st.selectedHeight}p"
        st.auto && st.selectedHeight > 0 -> "Otomatis · ${st.selectedHeight}p"
        st.selectedHeight > 0 -> "${st.selectedHeight}p"
        else -> "Opsi"
    }

    Box(modifier) {
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .background(Color(0x99000000), RoundedCornerShape(16.dp))
                .clickable { page = 0; open = true }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            when (page) {
                1 -> {
                    Item("‹ Kembali") { page = 0 }
                    Item("Otomatis (sesuai internet)", st.auto && !st.smooth) {
                        open = false
                        if (st.smooth) playback.setSmooth(false) else playback.setAuto()
                    }
                    st.qualities.sortedByDescending { it.height }.forEach { q ->
                        val sel = !st.auto && !st.smooth && q.height == st.selectedHeight
                        Item(q.label, sel) {
                            open = false
                            if (st.smooth) playback.setSmooth(false)
                            playback.setQuality(q)
                        }
                    }
                }
                2 -> {
                    Item("‹ Kembali") { page = 0 }
                    SPEEDS.forEach { sp ->
                        Item(speedLabel(sp), sp == st.speed) {
                            open = false
                            playback.setSpeed(sp)
                        }
                    }
                }
                3 -> {
                    Item("‹ Kembali") { page = 0 }
                    Item("Mati", st.subSelected < 0) { open = false; playback.setSubtitle(-1) }
                    st.subtitles.forEachIndexed { i, o ->
                        Item(o.label, st.subSelected == i) { open = false; playback.setSubtitle(i) }
                    }
                }
                4 -> {
                    Item("‹ Kembali") { page = 0 }
                    if (st.sleepEndMs > 0) Item("Matikan pengatur waktu") { open = false; playback.setSleepTimer(0) }
                    listOf(15, 30, 45, 60).forEach { m ->
                        Item("$m menit") { open = false; playback.setSleepTimer(m) }
                    }
                }
                else -> {
                    if (st.qualities.size > 1 && !st.isLive) {
                        Item("Kualitas: $label") { page = 1 }
                    }
                    Item("Kecepatan: ${speedLabel(st.speed)}") { page = 2 }
                    if (st.subtitles.isNotEmpty()) {
                        val cur = st.subtitles.getOrNull(st.subSelected)?.label ?: "Mati"
                        Item("Subtitle: $cur") { page = 3 }
                    }
                    if (!st.isLive) {
                        Item(if (st.audioOnly) "Mode audio saja: aktif" else "Mode audio saja: mati") {
                            open = false
                            playback.setAudioOnly(!st.audioOnly)
                        }
                    }
                    if (st.qualities.size > 1 && !st.isLive) {
                        Item(if (st.smooth) "Mode Lancar 720p: aktif" else "Mode Lancar 720p: mati") {
                            open = false
                            playback.setSmooth(!st.smooth)
                        }
                    }
                    val sleepLabel = if (st.sleepEndMs > 0) {
                        val left = ((st.sleepEndMs - SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(1)
                        "aktif, sisa ±$left mnt"
                    } else "mati"
                    Item("Waktu tidur: $sleepLabel") { page = 4 }
                    Item("Putar video berikutnya") { open = false; playback.skipNext() }
                }
            }
        }
    }
}

@Composable
private fun Item(text: String, selected: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                color = if (selected) G.Gold else G.Text,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        },
        onClick = onClick
    )
}
