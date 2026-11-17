package id.gatra.app.data

import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

const val WEB_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

/** Jembatan antara NewPipeExtractor dan OkHttp. */
class GatraDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val url = request.url()
        val data = request.dataToSend()

        var body: RequestBody? = data?.toRequestBody(null, 0, data.size)
        if (body == null && (method == "POST" || method == "PUT" || method == "PATCH")) {
            body = ByteArray(0).toRequestBody(null, 0, 0)
        }

        val builder = okhttp3.Request.Builder()
            .url(url)
            .method(method, body)
            .header("User-Agent", WEB_USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        val resp = client.newCall(builder.build()).execute()
        if (resp.code == 429) {
            resp.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }
        val text = resp.body?.string()
        val latestUrl = resp.request.url.toString()
        return Response(resp.code, resp.message, resp.headers.toMultimap(), text, latestUrl)
    }
}
