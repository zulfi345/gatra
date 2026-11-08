package id.gatra.app.data

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/** Satu video di daftar (beranda, hasil cari, riwayat, dll). */
data class VideoItem(
    val url: String,
    val title: String,
    val channel: String,
    val channelUrl: String?,
    val thumb: String?,
    val durationSec: Long,
    val views: Long,
    val date: String?,
    val publishedMs: Long = 0L,
    val live: Boolean = false
)

data class ChannelItem(
    val url: String,
    val name: String,
    val avatar: String?,
    val subscribers: Long,
    val description: String?
)

/** Pilih gambar berukuran sedang (sekitar 360px) dari daftar gambar extractor. */
fun List<Image>.bestUrl(): String? {
    if (isEmpty()) return null
    val known = filter { it.height > 0 }
    val pick = known.minByOrNull { abs(it.height - 360) } ?: first()
    return pick.url
}

fun StreamInfoItem.toVideo(fallbackChannel: String? = null, fallbackChannelUrl: String? = null): VideoItem =
    VideoItem(
        url = url,
        title = name ?: "",
        channel = (uploaderName ?: "").ifBlank { fallbackChannel ?: "" },
        channelUrl = uploaderUrl ?: fallbackChannelUrl,
        thumb = thumbnails.bestUrl(),
        durationSec = duration,
        views = viewCount,
        date = textualUploadDate,
        live = streamType == StreamType.LIVE_STREAM
    )

fun ChannelInfoItem.toChannel(): ChannelItem =
    ChannelItem(
        url = url,
        name = name ?: "",
        avatar = thumbnails.bestUrl(),
        subscribers = subscriberCount,
        description = description
    )

private val ID_LOCALE: Locale = Locale.forLanguageTag("id-ID")

fun formatDuration(sec: Long): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

private fun dec(v: Double): String =
    if (v >= 10) v.toLong().toString()
    else String.format(ID_LOCALE, "%.1f", v).removeSuffix(",0").removeSuffix(".0")

/** 1200 -> "1,2 rb", 3_400_000 -> "3,4 jt". */
fun compactCount(n: Long): String = when {
    n < 0 -> ""
    n >= 1_000_000_000L -> dec(n / 1_000_000_000.0) + " M"
    n >= 1_000_000L -> dec(n / 1_000_000.0) + " jt"
    n >= 1_000L -> dec(n / 1_000.0) + " rb"
    else -> n.toString()
}

fun relativeTime(ms: Long): String {
    if (ms <= 0) return ""
    val diff = System.currentTimeMillis() - ms
    val min = diff / 60_000
    return when {
        min < 1 -> "baru saja"
        min < 60 -> "$min menit lalu"
        min < 60 * 24 -> "${min / 60} jam lalu"
        min < 60 * 24 * 30 -> "${min / (60 * 24)} hari lalu"
        min < 60 * 24 * 365 -> "${min / (60 * 24 * 30)} bulan lalu"
        else -> "${min / (60 * 24 * 365)} tahun lalu"
    }
}

fun VideoItem.metaLine(includeChannel: Boolean = true): String {
    val parts = ArrayList<String>()
    if (includeChannel && channel.isNotBlank()) parts += channel
    if (live) parts += "Siaran langsung"
    else if (views >= 0) parts += "${compactCount(views)} x ditonton"
    if (!date.isNullOrBlank()) parts += date
    return parts.joinToString(" • ")
}

fun friendlyError(e: Throwable): String = when {
    e is IOException -> "Koneksi bermasalah. Periksa internet lalu coba lagi."
    e.javaClass.simpleName.contains("ReCaptcha") ->
        "YouTube sedang membatasi permintaan. Tunggu beberapa menit lalu coba lagi."
    else -> "Terjadi masalah: ${e.message ?: e.javaClass.simpleName}"
}
