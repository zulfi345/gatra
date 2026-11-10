package id.gatra.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import id.gatra.app.GatraApp
import id.gatra.app.data.AppSettings
import id.gatra.app.data.DL_DONE
import id.gatra.app.data.DL_FAILED
import id.gatra.app.data.DL_RUNNING
import id.gatra.app.data.DownloadEntity
import id.gatra.app.data.Library
import id.gatra.app.data.WEB_USER_AGENT
import id.gatra.app.data.bestUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Ringkasan unduhan yang sedang berjalan (untuk notifikasi). */
data class DownloadStatus(val count: Int = 0, val percent: Int = 0)

/**
 * Pengunduh video. Berkas disimpan di folder khusus aplikasi dan bisa dilanjutkan bila terputus.
 * Pengunduhan berjalan di proses aplikasi; [DownloadService] hanya menahan notifikasi agar tidak dimatikan sistem.
 */
class DownloadCenter(
    private val context: Context,
    private val library: Library,
    private val http: OkHttpClient,
    private val settings: AppSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val progress = ConcurrentHashMap<String, IntArray>() // [persen]

    private val _status = MutableStateFlow(DownloadStatus())
    val status: StateFlow<DownloadStatus> = _status.asStateFlow()

    private val dir: File
        get() = (context.getExternalFilesDir("unduhan") ?: File(context.filesDir, "unduhan")).also { it.mkdirs() }

    fun isRunning(url: String): Boolean = jobs[url]?.isActive == true

    private fun refreshStatus() {
        val running = jobs.filter { it.value.isActive }.keys
        val pct = if (running.isEmpty()) 0 else running.map { progress[it]?.get(0) ?: 0 }.average().toInt()
        _status.value = DownloadStatus(running.size, pct)
    }

    /** Mulai unduh. Mengembalikan pesan kesalahan, atau null bila berhasil dimulai. */
    fun start(info: StreamInfo, option: QualityOption?, audioOnly: Boolean): String? {
        val url = info.url ?: return "Alamat video tidak ditemukan."
        if (isRunning(url)) return "Video ini sedang diunduh."
        if (settings.wifiOnlyDownload && isMeteredNetwork(context)) {
            return "Pengaturan “Unduh hanya lewat Wi-Fi” aktif. Sambungkan Wi-Fi atau matikan pengaturan itu."
        }
        val audio = pickAudio(info)
        val wantAudioOnly = audioOnly || option == null
        val video = if (wantAudioOnly) null else option?.video
        val needAudio = wantAudioOnly || option?.muxed != true
        if (needAudio && audio == null) return "Aliran audio tidak ditemukan untuk video ini."
        if (!wantAudioOnly && video == null) return "Kualitas yang dipilih tidak tersedia."

        val id = (info.id ?: url.hashCode().toString()).replace(Regex("[^A-Za-z0-9_-]"), "_")
        val quality = if (wantAudioOnly) 0 else option?.height ?: 0
        val videoFile = video?.let { File(dir, "${id}_$quality.${it.format?.suffix ?: "mp4"}") }
        val audioFile = if (needAudio) File(dir, "${id}_a.${audio?.format?.suffix ?: "m4a"}") else null
        val entity = DownloadEntity(
            url = url,
            title = info.name ?: "",
            channel = info.uploaderName ?: "",
            thumbnail = info.thumbnails.bestUrl(),
            duration = info.duration,
            quality = quality,
            videoPath = videoFile?.absolutePath,
            audioPath = audioFile?.absolutePath,
            totalBytes = 0L,
            doneBytes = 0L,
            status = DL_RUNNING,
            createdAt = System.currentTimeMillis()
        )
        val videoUrl = video?.content
        val audioUrl = if (needAudio) audio?.content else null
        launchJob(entity, videoUrl, audioUrl)
        DownloadService.start(context)
        return null
    }

    private fun launchJob(e: DownloadEntity, videoUrl: String?, audioUrl: String?) {
        progress[e.url] = intArrayOf(0)
        val j = scope.launch {
            try {
                library.saveDownload(e)
                val total = (videoUrl?.let { clenOf(it) } ?: 0L) + (audioUrl?.let { clenOf(it) } ?: 0L)
                var done = 0L
                var lastSave = 0L
                val onBytes: suspend (Long) -> Unit = { n ->
                    done += n
                    if (total > 0) progress[e.url]?.set(0, (done * 100 / total).toInt().coerceIn(0, 100))
                    val now = System.currentTimeMillis()
                    if (now - lastSave > 800) {
                        lastSave = now
                        library.setDownloadProgress(e.url, done, total)
                        refreshStatus()
                    }
                }
                if (videoUrl != null && e.videoPath != null) fetch(videoUrl, File(e.videoPath), onBytes)
                if (audioUrl != null && e.audioPath != null) fetch(audioUrl, File(e.audioPath), onBytes)
                val size = (e.videoPath?.let { File(it).length() } ?: 0L) + (e.audioPath?.let { File(it).length() } ?: 0L)
                library.saveDownload(e.copy(totalBytes = size, doneBytes = size, status = DL_DONE))
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                library.setDownloadStatus(e.url, DL_FAILED)
            } finally {
                jobs.remove(e.url)
                progress.remove(e.url)
                refreshStatus()
            }
        }
        jobs[e.url] = j
        refreshStatus()
    }

    /** Coba lagi unduhan yang gagal; bagian yang sudah terunduh dilanjutkan. */
    fun retry(d: DownloadEntity, onResult: (String?) -> Unit) {
        scope.launch {
            try {
                val info = StreamInfo.getInfo(ServiceList.YouTube, d.url)
                val options = buildQualityOptions(info, false)
                val audioOnly = d.quality == 0
                val option = if (audioOnly) null else options.firstOrNull { it.height == d.quality } ?: options.firstOrNull()
                onResult(start(info, option, audioOnly))
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                onResult("Gagal mengambil data video. Periksa internet lalu coba lagi.")
            }
        }
    }

    /** Hapus unduhan beserta berkasnya. */
    fun delete(d: DownloadEntity) {
        jobs[d.url]?.cancel()
        scope.launch {
            d.videoPath?.let { runCatching { File(it).delete() } }
            d.audioPath?.let { runCatching { File(it).delete() } }
            library.deleteDownload(d.url)
            refreshStatus()
        }
    }

    private fun clenOf(url: String): Long? = Uri.parse(url).getQueryParameter("clen")?.toLongOrNull()

    private fun userAgentFor(uri: Uri): String {
        val clientName = uri.getQueryParameter("c").orEmpty()
        return when {
            clientName.startsWith("ANDROID") -> ExtractorUserAgents.android
            clientName.startsWith("IOS") -> ExtractorUserAgents.ios
            else -> WEB_USER_AGENT
        }
    }

    /** Unduh satu berkas per potongan 10 MB (cara yang diterima YouTube); lanjut dari ukuran berkas bila ada. */
    private suspend fun fetch(urlStr: String, out: File, onBytes: suspend (Long) -> Unit) {
        val uri = Uri.parse(urlStr)
        val clen = uri.getQueryParameter("clen")?.toLongOrNull()
        val ua = userAgentFor(uri)
        out.parentFile?.mkdirs()
        if (clen == null && out.exists()) out.delete()
        var pos = if (out.exists()) out.length() else 0L
        if (clen != null && pos > clen) {
            out.delete()
            pos = 0L
        }
        if (pos > 0) onBytes(pos)
        if (clen != null && pos >= clen) return

        FileOutputStream(out, pos > 0).use { fos ->
            val buf = ByteArray(64 * 1024)
            if (clen != null) {
                var n = 0
                while (pos < clen) {
                    val end = minOf(pos + CHUNK - 1, clen - 1)
                    n++
                    val target = uri.buildUpon()
                        .appendQueryParameter("range", "$pos-$end")
                        .appendQueryParameter("rn", n.toString())
                        .build().toString()
                    val req = Request.Builder().url(target).header("User-Agent", ua).build()
                    http.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        val body = r.body ?: throw IOException("Respons kosong")
                        body.byteStream().use { input ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buf)
                                if (read == -1) break
                                fos.write(buf, 0, read)
                                pos += read
                                onBytes(read.toLong())
                            }
                        }
                    }
                }
            } else {
                val req = Request.Builder().url(urlStr).header("User-Agent", ua).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("Respons kosong")
                    body.byteStream().use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buf)
                            if (read == -1) break
                            fos.write(buf, 0, read)
                            onBytes(read.toLong())
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val CHUNK = 10L * 1024 * 1024
    }
}

/** Layanan latar depan yang menampilkan notifikasi selama ada unduhan berjalan. */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val center = (application as GatraApp).downloads
        val first = buildNotification(center.status.value)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, first)
        }
        scope.launch {
            center.status.collect { st ->
                if (st.count <= 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val nm = (getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager)
                    nm.notify(NOTIF_ID, buildNotification(st))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = (getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Unduhan video", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(st: DownloadStatus): Notification {
        val text = if (st.count <= 1) "Mengunduh video… ${st.percent}%" else "Mengunduh ${st.count} video… ${st.percent}%"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Gatra")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, st.percent, st.percent == 0)
            .build()
    }

    companion object {
        private const val CHANNEL = "gatra_downloads"
        private const val NOTIF_ID = 4711

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (_: Exception) {
                // Unduhan tetap berjalan tanpa notifikasi bila sistem menolak layanan latar depan.
            }
        }
    }
}
