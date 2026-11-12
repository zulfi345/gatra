package id.gatra.app.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Algoritma rekomendasi lokal Gatra. Semua dihitung di HP dari kebiasaanmu; tidak ada data yang dikirim keluar.
 *
 * 1. Skor kecocokan: bobot channel + kata kunci judul (dipelajari dari riwayat dan tindakanmu).
 * 2. Bobot meluruh separuh setiap 30 hari supaya selera lama tidak mendominasi.
 * 3. Kesegaran dan sedikit popularitas ikut dinilai.
 * 4. Keragaman: tidak pernah 2 video berturut-turut dari channel yang sama; maksimal 2 video per channel
 *    di 20 teratas dan 3 di seluruh daftar; judul yang topiknya mirip dengan video sebelumnya diturunkan.
 * 5. Jeda: channel yang baru kamu tonton dalam 2 hari terakhir diturunkan sedikit supaya yang lain ikut tampil.
 * 6. Eksplorasi: setiap slot ke-3 diisi video dari channel/topik yang belum akrab.
 */
object Ranking {
    private const val HALF_LIFE_DAYS = 30.0

    private val STOP = setOf(
        "yang", "dan", "di", "ke", "dari", "untuk", "ini", "itu", "dengan", "pada", "dalam", "atau", "juga",
        "ada", "adalah", "akan", "saya", "kamu", "kita", "kami", "aku", "nya", "para", "sama", "bisa", "tidak",
        "the", "and", "for", "you", "your", "with", "this", "that", "from", "what", "how", "are", "was", "have",
        "official", "video", "videos", "full", "part", "episode", "eps", "live", "new", "terbaru", "hd", "mv",
        "channel", "youtube", "shorts", "short", "vs", "lagi", "banget", "paling", "semua", "jadi", "kalau"
    )

    fun decayed(weight: Double, updatedAt: Long, now: Long): Double {
        val days = (now - updatedAt).coerceAtLeast(0L) / 86_400_000.0
        return weight * 0.5.pow(days / HALF_LIFE_DAYS)
    }

    fun channelKey(channelUrl: String?, channel: String): String {
        val u = channelUrl?.trim().orEmpty()
        return "ch:" + if (u.isNotEmpty()) u else channel.trim().lowercase()
    }

    fun keywords(title: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in title.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))) {
            if (raw.length < 3 || raw.length > 20 || raw in STOP || raw.all { it.isDigit() }) continue
            out += raw
            if (out.size >= 8) break
        }
        return out.toList()
    }

    private class Scored(
        val e: RecEntity,
        val channel: String,
        val score: Double,
        val familiarity: Double,
        val appeal: Double,
        val kws: Set<String>
    )

    fun rank(
        pool: List<RecEntity>,
        signals: List<SignalEntity>,
        recentChannels: Map<String, Int> = emptyMap(),
        now: Long = System.currentTimeMillis()
    ): List<VideoItem> {
        if (pool.isEmpty()) return emptyList()
        val w = HashMap<String, Double>(signals.size * 2)
        for (s in signals) w[s.name] = decayed(s.weight, s.updatedAt, now)
        val day = now / 86_400_000L

        val scored = pool.map { e ->
            val ck = channelKey(e.channelUrl, e.channel)
            val kws = keywords(e.title)
            // Bobot channel dibatasi lebih kecil supaya satu channel tidak menenggelamkan yang lain.
            val chScore = tanh((w[ck] ?: 0.0) / 8.0) * 2.0
            val kwVals = kws.map { tanh((w["kw:$it"] ?: 0.0) / 5.0) }
            val kwScore = (kwVals.sortedDescending().take(3).sum() + kwVals.filter { it < 0 }.sum()) * 0.8
            val ageH = (now - e.savedAt).coerceAtLeast(0L) / 3_600_000.0
            val fresh = 0.8 * exp(-ageH / 48.0)
            val pop = if (e.views > 0) log10(e.views.toDouble() + 1.0) / 10.0 else 0.0
            val jitter = (((e.url.hashCode() xor day.toInt()) and 0x7fffffff) % 1000) / 1000.0 * 0.15
            // Jeda: channel yang baru ditonton diturunkan sedikit.
            val cooldown = minOf(1.2, 0.4 * (recentChannels[ck] ?: 0))
            Scored(
                e, ck, chScore + kwScore + fresh + pop + jitter - cooldown,
                abs(chScore) + abs(kwScore), fresh + pop + jitter, kws.toSet()
            )
        }.filter { it.score > -2.5 } // yang jelas tidak disukai disingkirkan

        val remaining = scored.sortedByDescending { it.score }.toMutableList()
        val result = ArrayList<Scored>()
        val perChannel = HashMap<String, Int>()
        while (remaining.isNotEmpty() && result.size < 60) {
            val limit = if (result.size < 20) 2 else 3
            val lastChannel = result.lastOrNull()?.channel
            var candidates = remaining.filter { (perChannel[it.channel] ?: 0) < limit && it.channel != lastChannel }
            if (candidates.isEmpty()) candidates = remaining.filter { it.channel != lastChannel }
            if (candidates.isEmpty()) candidates = remaining.toList()

            val recentWords = HashSet<String>()
            for (r in result.takeLast(3)) recentWords.addAll(r.kws)

            var pick: Scored? = null
            if (result.size % 3 == 2) {
                pick = candidates.filter { it.familiarity < 0.5 && it.score > -1.0 }.maxByOrNull { it.appeal }
            }
            val chosen = pick ?: candidates.maxByOrNull { c ->
                val overlap = minOf(3, c.kws.count { it in recentWords })
                c.score - 0.7 * (perChannel[c.channel] ?: 0) - 0.35 * overlap
            } ?: remaining.first()

            result += chosen
            perChannel[chosen.channel] = (perChannel[chosen.channel] ?: 0) + 1
            remaining.remove(chosen)
        }
        return result.map {
            val e = it.e
            VideoItem(
                url = e.url, title = e.title, channel = e.channel, channelUrl = e.channelUrl,
                thumb = e.thumbnail, durationSec = e.duration, views = e.views, date = null,
                avatar = e.avatar
            )
        }
    }
}
