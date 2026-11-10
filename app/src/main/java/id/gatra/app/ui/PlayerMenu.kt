package id.gatra.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import id.gatra.app.player.PlaybackController
import id.gatra.app.player.PlayerState
import id.gatra.app.ui.theme.G

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun speedLabel(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x".replace('.', ',')

/**
 * Gir di pojok kanan atas video: HANYA untuk memilih kualitas (Otomatis, 1080p, 720p, 480p, dst).
 * Pengaturan lain ada di gir pada bilah kontrol bawah ([PlayerSettingsMenu]).
 */
@Composable
fun QualityMenu(st: PlayerState, playback: PlaybackController, modifier: Modifier) {
    if (st.qualities.size <= 1 || st.isLive) return
    var open by remember { mutableStateOf(false) }

    Box(modifier) {
        val cur = st.qualities.firstOrNull { it.height == st.selectedHeight }?.label
            ?: if (st.selectedHeight > 0) "${st.selectedHeight}p" else ""
        val label = when {
            st.smooth -> "720p"
            st.auto -> if (cur.isEmpty()) "Auto" else "Auto • $cur"
            else -> cur.ifEmpty { "Auto" }
        }
        Text(
            label,
            color = Color.White,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier
                .background(Color(0x66000000), RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 9.dp)
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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
    }
}

/**
 * Menu pengaturan lengkap, dibuka dari gir di bilah kontrol bawah pemutar:
 * bahasa audio, kecepatan, subtitle, mode audio saja, Mode Lancar, waktu tidur, sebelumnya/berikutnya.
 * [modifier] menentukan letak jangkar menu (dekat gir bawah).
 */
@Composable
fun PlayerSettingsMenu(
    st: PlayerState,
    playback: PlaybackController,
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier
) {
    var page by remember { mutableIntStateOf(0) } // 0 utama, 2 kecepatan, 3 subtitle, 4 tidur, 5 bahasa audio
    LaunchedEffect(open) { if (open) page = 0 }

    Box(modifier) {
        DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
            when (page) {
                2 -> {
                    Item("‹ Kembali") { page = 0 }
                    SPEEDS.forEach { sp ->
                        Item(speedLabel(sp), sp == st.speed) {
                            onDismiss()
                            playback.setSpeed(sp)
                        }
                    }
                }
                3 -> {
                    Item("‹ Kembali") { page = 0 }
                    Item("Mati", st.subSelected < 0) { onDismiss(); playback.setSubtitle(-1) }
                    st.subtitles.forEachIndexed { i, o ->
                        Item(o.label, st.subSelected == i) { onDismiss(); playback.setSubtitle(i) }
                    }
                }
                5 -> {
                    Item("‹ Kembali") { page = 0 }
                    st.audioTracks.forEach { t ->
                        Item(t.label, st.audioTrackSel == t.id) {
                            onDismiss()
                            playback.setAudioTrack(t.id)
                        }
                    }
                }
                4 -> {
                    Item("‹ Kembali") { page = 0 }
                    if (st.sleepEndMs > 0) Item("Matikan pengatur waktu") { onDismiss(); playback.setSleepTimer(0) }
                    listOf(15, 30, 45, 60).forEach { m ->
                        Item("$m menit") { onDismiss(); playback.setSleepTimer(m) }
                    }
                }
                else -> {
                    if (st.audioTracks.isNotEmpty()) {
                        val cur = st.audioTracks.firstOrNull { it.id == st.audioTrackSel }?.label ?: "Asli"
                        Item("Bahasa audio: $cur") { page = 5 }
                    }
                    Item("Kecepatan: ${speedLabel(st.speed)}") { page = 2 }
                    if (st.subtitles.isNotEmpty()) {
                        val cur = st.subtitles.getOrNull(st.subSelected)?.label ?: "Mati"
                        Item("Subtitle: $cur") { page = 3 }
                    }
                    if (!st.isLive) {
                        Item(if (st.audioOnly) "Mode audio saja: aktif" else "Mode audio saja: mati") {
                            onDismiss()
                            playback.setAudioOnly(!st.audioOnly)
                        }
                    }
                    if (st.qualities.size > 1 && !st.isLive) {
                        Item(if (st.smooth) "Mode Lancar 720p: aktif" else "Mode Lancar 720p: mati") {
                            onDismiss()
                            playback.setSmooth(!st.smooth)
                        }
                    }
                    val sleepLabel = if (st.sleepEndMs > 0) {
                        val left = ((st.sleepEndMs - SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(1)
                        "aktif, sisa ±$left mnt"
                    } else "mati"
                    Item("Waktu tidur: $sleepLabel") { page = 4 }
                    if (st.hasPrevious) Item("Putar video sebelumnya") { onDismiss(); playback.playPrevious() }
                    Item("Putar video berikutnya") { onDismiss(); playback.skipNext() }
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
