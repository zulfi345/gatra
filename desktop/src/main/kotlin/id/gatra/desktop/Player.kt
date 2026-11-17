@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)

package id.gatra.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.gatra.app.data.VideoItem
import id.gatra.app.data.friendlyError
import id.gatra.app.data.metaLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

class QualityOpt(
    val height: Int,
    val label: String,
    val video: VideoStream,
    val muxed: Boolean
)

private fun qualityLabel(height: Int, fps: Int): String = if (fps > 30) "${height}p$fps" else "${height}p"

/** Pilihan kualitas: video terpisah (sampai 1080p) + audio, atau berkas gabungan bila itu satu-satunya. */
private fun buildQualities(info: StreamInfo): List<QualityOpt> {
    val out = ArrayList<QualityOpt>()
    if (pickAudio(info) != null) {
        info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..1080 }
            .groupBy { it.height }
            .forEach { (h, list) ->
                val best = list.sortedWith(
                    compareBy<VideoStream> { if (it.format == MediaFormat.MPEG_4) 0 else 1 }
                        .thenByDescending { it.bitrate }
                ).first()
                out += QualityOpt(h, qualityLabel(h, best.fps), best, false)
            }
    }
    info.videoStreams
        .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height > 0 }
        .forEach { v ->
            if (out.none { it.height == v.height }) out += QualityOpt(v.height, qualityLabel(v.height, v.fps), v, true)
        }
    return out.sortedBy { it.height }
}

private fun pickAudio(info: StreamInfo): AudioStream? {
    val all = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
    if (all.isEmpty()) return null
    val originals = all.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
    val pool = originals.ifEmpty { all }
    return pool.sortedWith(
        compareBy<AudioStream> { if (it.format == MediaFormat.M4A) 0 else 1 }
            .thenByDescending { it.averageBitrate }
    ).first()
}

private fun fmtTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

@Composable
fun PlayerScreen(
    video: VideoItem,
    vlcOk: Boolean,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val comp = remember {
        if (vlcOk) runCatching { EmbeddedMediaPlayerComponent() }.getOrNull() else null
    }
    DisposableEffect(comp) {
        onDispose {
            runCatching { comp?.mediaPlayer()?.controls()?.stop() }
            runCatching { comp?.release() }
        }
    }

    var info by remember { mutableStateOf<StreamInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var qualities by remember { mutableStateOf<List<QualityOpt>>(emptyList()) }
    var selected by remember { mutableStateOf(0) }
    var live by remember { mutableStateOf(false) }

    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var lengthMs by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(80f) }
    var qualityMenu by remember { mutableStateOf(false) }

    fun startPlayback(i: StreamInfo, opts: List<QualityOpt>, index: Int, startMs: Long) {
        val c = comp ?: return
        val mp = c.mediaPlayer()
        val proxy = Svc.proxy
        val isLive = i.streamType == StreamType.LIVE_STREAM && !i.hlsUrl.isNullOrBlank()
        if (isLive) {
            mp.media().play(i.hlsUrl)
        } else {
            val q = opts.getOrNull(index) ?: return
            val videoMime = q.video.format?.mimeType ?: "video/mp4"
            val videoUrl = proxy.urlFor(q.video.content, videoMime)
            val audio = if (q.muxed) null else pickAudio(i)
            if (audio != null) {
                val audioMime = audio.format?.mimeType ?: "audio/mp4"
                val audioUrl = proxy.urlFor(audio.content, audioMime)
                mp.media().play(videoUrl, ":input-slave=$audioUrl")
            } else {
                mp.media().play(videoUrl)
            }
        }
        mp.audio().setVolume(volume.toInt())
        if (startMs > 0L) {
            scope.launch {
                delay(1500)
                runCatching { mp.controls().setTime(startMs) }
            }
        }
    }

    LaunchedEffect(video.url) {
        error = null
        info = null
        try {
            val i = DesktopRepo.streams(video.url)
            val opts = buildQualities(i)
            val isLive = i.streamType == StreamType.LIVE_STREAM && !i.hlsUrl.isNullOrBlank()
            if (opts.isEmpty() && !isLive) {
                error = "Tidak ada aliran video yang bisa diputar untuk video ini."
                return@LaunchedEffect
            }
            val start = opts.indexOfLast { it.height <= 720 }.let { if (it >= 0) it else 0 }
            info = i
            live = isLive
            qualities = opts
            selected = start
            startPlayback(i, opts, start, 0L)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = friendlyError(e)
        }
    }

    LaunchedEffect(comp) {
        val c = comp ?: return@LaunchedEffect
        while (true) {
            delay(500)
            try {
                val st = c.mediaPlayer().status()
                playing = st.isPlaying
                lengthMs = st.length()
                if (!dragging) positionMs = st.time()
            } catch (_: Throwable) {
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("< Kembali") }
            Spacer(Modifier.width(8.dp))
            Text(
                video.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val panel = comp
            if (!vlcOk || panel == null) {
                VlcMissing()
            } else {
                SwingPanel(modifier = Modifier.fillMaxSize(), factory = { panel })
                if (info == null && error == null) CircularProgressIndicator()
            }
        }

        if (error != null) {
            Text(
                error ?: "",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp)
            )
        }

        if (vlcOk && comp != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    val mp = comp.mediaPlayer()
                    if (mp.status().isPlaying) mp.controls().pause() else mp.controls().play()
                }) { Text(if (playing) "Jeda" else "Putar") }
                Spacer(Modifier.width(12.dp))
                Text(fmtTime(positionMs), fontSize = 12.sp)
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, maxOf(lengthMs, 1L).toFloat()),
                    onValueChange = {
                        dragging = true
                        positionMs = it.toLong()
                    },
                    onValueChangeFinished = {
                        runCatching { comp.mediaPlayer().controls().setTime(positionMs) }
                        dragging = false
                    },
                    valueRange = 0f..maxOf(lengthMs, 1L).toFloat(),
                    enabled = !live,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(if (live) "LIVE" else fmtTime(lengthMs), fontSize = 12.sp)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Volume", fontSize = 12.sp)
                Slider(
                    value = volume,
                    onValueChange = {
                        volume = it
                        runCatching { comp.mediaPlayer().audio().setVolume(it.toInt()) }
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.width(160.dp)
                )
                Spacer(Modifier.weight(1f))
                if (qualities.isNotEmpty() && !live) {
                    Box {
                        TextButton(onClick = { qualityMenu = true }) {
                            Text("Kualitas: ${qualities.getOrNull(selected)?.label ?: "-"}")
                        }
                        DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                            qualities.forEachIndexed { index, q ->
                                DropdownMenuItem(
                                    text = { Text(q.label) },
                                    onClick = {
                                        qualityMenu = false
                                        val i = info
                                        if (i != null && index != selected) {
                                            val pos = positionMs
                                            selected = index
                                            startPlayback(i, qualities, index, pos)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                Button(onClick = onToggleFullscreen) { Text(if (fullscreen) "Keluar layar penuh" else "Layar penuh") }
            }
        }

        if (!fullscreen) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(video.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text(video.metaLine(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun VlcMissing() {
    Column(
        Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Pemutar VLC belum terpasang", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Gatra memakai VLC (versi 64-bit) untuk memutar video di PC. " +
                "Pasang VLC dari videolan.org, lalu tutup dan buka Gatra lagi.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
