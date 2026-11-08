package id.gatra.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import id.gatra.app.data.WEB_USER_AGENT
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Sumber data HTTP untuk aliran YouTube.
 *
 * YouTube menolak / memperlambat permintaan sebuah berkas video utuh dalam satu kali unduh.
 * Karena itu berkas diambil per potongan (parameter `range` di URL) selama URL punya
 * parameter `clen` (ukuran total). Tanpa `clen` (misalnya manifes HLS) dipakai permintaan
 * biasa dengan header Range. Ini cara yang sama dipakai aplikasi NewPipe.
 */
class YtChunkedDataSource(private val client: OkHttpClient) : BaseDataSource(true) {

    private var spec: DataSpec? = null
    private var response: Response? = null
    private var stream: InputStream? = null
    private var position = 0L
    private var totalEnd = UNSET
    private var chunkEnd = 0L
    private var remaining = UNSET
    private var chunked = false
    private var opened = false
    private var requestNumber = 0

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        transferInitializing(dataSpec)
        position = dataSpec.position

        val clen = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        chunked = clen != null && dataSpec.uri.getQueryParameter("range") == null
        totalEnd = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> position + dataSpec.length
            clen != null -> clen
            else -> UNSET
        }
        remaining = if (totalEnd != UNSET) totalEnd - position else UNSET
        if (remaining < 0 && remaining != UNSET) throw IOException("Posisi di luar jangkauan")

        openChunk()
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    private fun userAgentFor(uri: Uri): String {
        val clientName = uri.getQueryParameter("c").orEmpty()
        return when {
            clientName.startsWith("ANDROID") -> ExtractorUserAgents.android
            clientName.startsWith("IOS") -> ExtractorUserAgents.ios
            else -> WEB_USER_AGENT
        }
    }

    private fun openChunk() {
        closeStream()
        val s = spec ?: throw IOException("DataSpec kosong")
        val builder = Request.Builder().header("User-Agent", userAgentFor(s.uri))
        val target: String
        if (chunked) {
            var end = position + CHUNK_SIZE - 1
            if (totalEnd != UNSET && end > totalEnd - 1) end = totalEnd - 1
            chunkEnd = end + 1
            requestNumber++
            target = s.uri.buildUpon()
                .appendQueryParameter("range", "$position-$end")
                .appendQueryParameter("rn", requestNumber.toString())
                .build().toString()
        } else {
            target = s.uri.toString()
            if (position > 0L || totalEnd != UNSET) {
                val last = if (totalEnd != UNSET) (totalEnd - 1).toString() else ""
                builder.header("Range", "bytes=$position-$last")
            }
        }
        val resp = client.newCall(builder.url(target).build()).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw IOException("HTTP $code saat memuat aliran video")
        }
        response = resp
        val body = resp.body ?: throw IOException("Respons kosong")
        val input = body.byteStream()
        if (!chunked && position > 0L && resp.code == 200) skipFully(input, position)
        stream = input
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) {
                if (input.read() == -1) throw EOFException()
                left--
            } else {
                left -= n
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (remaining == UNSET) length else minOf(length.toLong(), remaining).toInt()
        val input = stream ?: throw IOException("Aliran belum dibuka")
        var n = input.read(buffer, offset, want)
        if (n == -1 && chunked && position >= chunkEnd && (totalEnd == UNSET || position < totalEnd)) {
            openChunk()
            n = (stream ?: throw IOException("Aliran belum dibuka")).read(buffer, offset, want)
        }
        if (n == -1) {
            if (remaining != UNSET && remaining > 0L) throw EOFException()
            return C.RESULT_END_OF_INPUT
        }
        position += n
        if (remaining != UNSET) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        try {
            closeStream()
        } finally {
            spec = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun closeStream() {
        try {
            stream?.close()
        } catch (_: IOException) {
        }
        response?.close()
        stream = null
        response = null
    }

    private companion object {
        const val UNSET = -1L
        const val CHUNK_SIZE = 10L * 1024 * 1024
    }
}

/** User-Agent klien Android/iOS diambil dari extractor lewat refleksi; ada cadangan bila berubah. */
internal object ExtractorUserAgents {
    private const val HELPER = "org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper"

    val android: String by lazy {
        fromHelper("getAndroidUserAgent")
            ?: "com.google.android.youtube/19.28.35 (Linux; U; Android 14; GB) gzip"
    }
    val ios: String by lazy {
        fromHelper("getIosUserAgent")
            ?: "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
    }

    private fun fromHelper(method: String): String? = try {
        val cls = Class.forName(HELPER)
        val loc = Class.forName("org.schabi.newpipe.extractor.localization.Localization")
        val m = cls.getMethod(method, loc)
        m.invoke(null, *arrayOf<Any?>(null)) as? String
    } catch (e: Throwable) {
        null
    }
}
