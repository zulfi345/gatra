package id.gatra.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import id.gatra.app.data.WEB_USER_AGENT
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Server kecil di komputer sendiri (127.0.0.1) untuk VLC.
 *
 * YouTube menolak / memperlambat permintaan berkas video utuh dalam satu kali unduh. Jadi berkas
 * diambil per potongan (parameter `range` di URL), sama seperti versi Android (YtChunkedDataSource).
 * VLC cukup membuka alamat lokal ini dan boleh melompat (seek) seperti biasa.
 */
class StreamProxy(private val http: OkHttpClient) {

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    init {
        server.createContext("/s", HttpHandler { ex -> handle(ex) })
        server.executor = Executors.newCachedThreadPool(ThreadFactory { r ->
            Thread(r).apply { isDaemon = true }
        })
        server.start()
    }

    private val port: Int get() = server.address.port

    fun urlFor(remote: String, mime: String): String =
        "http://127.0.0.1:$port/s?t=${enc(mime)}&u=${enc(remote)}"

    fun stop() {
        try {
            server.stop(0)
        } catch (_: Exception) {
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun parseQuery(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            if (i <= 0) continue
            out[URLDecoder.decode(part.substring(0, i), "UTF-8")] =
                URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }
        return out
    }

    private fun userAgentFor(clientName: String?): String {
        val c = clientName.orEmpty()
        return when {
            c.startsWith("ANDROID") -> ExtractorUserAgents.android
            c.startsWith("IOS") -> ExtractorUserAgents.ios
            else -> WEB_USER_AGENT
        }
    }

    private fun handle(ex: HttpExchange) {
        try {
            val params = parseQuery(ex.requestURI.rawQuery.orEmpty())
            val remote = params["u"]
            if (remote.isNullOrBlank()) {
                ex.sendResponseHeaders(400, -1)
                return
            }
            val mime = params["t"] ?: "application/octet-stream"
            val url = remote.toHttpUrl()
            val clen = url.queryParameter("clen")?.toLongOrNull()
            val hasRange = url.queryParameter("range") != null

            // Tanpa ukuran total (mis. manifes HLS): serahkan langsung ke alamat aslinya.
            if (clen == null || hasRange) {
                ex.responseHeaders.add("Location", remote)
                ex.sendResponseHeaders(302, -1)
                return
            }

            var start = 0L
            var end = clen - 1
            var partial = false
            val rangeHeader = ex.requestHeaders.getFirst("Range")
            if (rangeHeader != null) {
                val m = Regex("""bytes=(\d*)-(\d*)""").find(rangeHeader)
                if (m != null) {
                    val a = m.groupValues[1]
                    val b = m.groupValues[2]
                    if (a.isNotEmpty()) {
                        start = a.toLong()
                        if (b.isNotEmpty()) end = minOf(b.toLong(), clen - 1)
                    } else if (b.isNotEmpty()) {
                        start = maxOf(0L, clen - b.toLong())
                    }
                    partial = true
                }
            }
            if (start > end || start >= clen) {
                ex.responseHeaders.add("Content-Range", "bytes */$clen")
                ex.sendResponseHeaders(416, -1)
                return
            }

            val length = end - start + 1
            ex.responseHeaders.add("Content-Type", mime)
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            if (partial) ex.responseHeaders.add("Content-Range", "bytes $start-$end/$clen")
            val status = if (partial) 206 else 200

            if (ex.requestMethod.equals("HEAD", ignoreCase = true)) {
                ex.responseHeaders.add("Content-Length", length.toString())
                ex.sendResponseHeaders(status, -1)
                return
            }

            ex.sendResponseHeaders(status, length)
            val ua = userAgentFor(url.queryParameter("c"))
            val buf = ByteArray(64 * 1024)
            ex.responseBody.use { out ->
                var pos = start
                var requestNumber = 0
                while (pos <= end) {
                    val chunkEnd = minOf(pos + CHUNK_SIZE - 1, end)
                    requestNumber++
                    val chunkUrl = url.newBuilder()
                        .addQueryParameter("range", "$pos-$chunkEnd")
                        .addQueryParameter("rn", requestNumber.toString())
                        .build()
                    val req = Request.Builder().url(chunkUrl).header("User-Agent", ua).build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} saat memuat aliran video")
                        val body = resp.body ?: throw IOException("Respons kosong")
                        var got = 0L
                        body.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                            }
                        }
                        if (got <= 0L) throw IOException("Potongan video kosong")
                        pos += got
                    }
                }
            }
        } catch (_: Exception) {
            // VLC menutup sambungan saat melompat / berhenti: itu normal, abaikan.
        } finally {
            try {
                ex.close()
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
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
