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
 * 4. Jatah genre: Beranda dibagi per genre. 70% untuk genre yang paling sering kamu tonton (dibagi sesuai
 *    porsi tontonanmu), 20% untuk genre "bertetangga", 10% untuk genre baru dan video yang belum
 *    dikenali genrenya. Isi tiap jatah boleh dari channel mana saja; tidak ada batas jumlah per channel.
 * 5. Keragaman halus: channel yang sudah banyak terpilih diturunkan sedikit (bukan dilarang), dan dua video
 *    berturut-turut dari channel yang sama dihindari bila ada pilihan lain.
 * 6. Jeda: channel yang baru kamu tonton dalam 2 hari terakhir diturunkan sedikit supaya yang lain ikut tampil.
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

    // ---------- Jatah genre ----------

    private const val SHARE_FAVORITE = 0.70
    private const val SHARE_NEIGHBOR = 0.20
    private const val SHARE_NEW = 0.10

    /** Porsi minat per genre (id genre -> bobot), dari riwayat tontonan dan tindakanmu, meluruh seiring waktu. */
    fun genreProfile(history: List<HistoryEntity>, signals: List<SignalEntity>, now: Long): Map<String, Double> {
        val raw = HashMap<String, Double>()
        for (h in history.take(200)) {
            val g = Genres.classify(h.title, h.channel) ?: continue
            val ageDays = (now - h.watchedAt).coerceAtLeast(0L) / 86_400_000.0
            raw[g] = (raw[g] ?: 0.0) + 0.5.pow(ageDays / HALF_LIFE_DAYS)
        }
        for (sig in signals) {
            if (!sig.name.startsWith("g:")) continue
            val id = sig.name.substring(2)
            raw[id] = (raw[id] ?: 0.0) + decayed(sig.weight, sig.updatedAt, now) * 0.5
        }
        return raw.filter { it.value > 0.05 }
    }

    /**
     * Jatah tampil per genre (jumlahnya 1.0). Kosong bila riwayat belum cukup: Beranda lalu dicampur rata.
     * Kunci "?" = video yang genrenya belum dikenali.
     */
    fun genreTargets(profile: Map<String, Double>, now: Long): Map<String, Double> {
        if (profile.values.sum() < 1.0) return emptyMap()
        val favorites = profile.entries.sortedByDescending { it.value }.take(5)
        val favSum = favorites.sumOf { it.value }
        val out = LinkedHashMap<String, Double>()
        for (f in favorites) out[f.key] = SHARE_FAVORITE * f.value / favSum
        val favIds = favorites.map { it.key }.toSet()

        val rnd = java.util.Random(now / 86_400_000L)
        val others = Genres.all.map { it.id }.filter { it !in favIds }.shuffled(rnd)
        var neighbors = favorites.take(3)
            .flatMap { Genres.byId(it.key)?.near ?: emptyList() }
            .filter { it !in favIds }
            .distinct()
            .take(4)
        if (neighbors.isEmpty()) neighbors = others.take(2)
        for (n in neighbors) out[n] = SHARE_NEIGHBOR / neighbors.size

        val fresh = others.filter { it !in neighbors }.take(2) + "?"
        for (n in fresh) out[n] = (out[n] ?: 0.0) + SHARE_NEW / fresh.size
        return out
    }

    private class Scored(
        val e: RecEntity,
        val channel: String,
        val genre: String,
        val score: Double,
        val kws: Set<String>
    )

    fun rank(
        pool: List<RecEntity>,
        signals: List<SignalEntity>,
        recentChannels: Map<String, Int> = emptyMap(),
        history: List<HistoryEntity> = emptyList(),
        now: Long = System.currentTimeMillis()
    ): List<VideoItem> {
        if (pool.isEmpty()) return emptyList()
        val w = HashMap<String, Double>(signals.size * 2)
        for (s in signals) w[s.name] = decayed(s.weight, s.updatedAt, now)
        val day = now / 86_400_000L

        val scored = pool.map { e ->
            val ck = channelKey(e.channelUrl, e.channel)
            val kws = keywords(e.title)
            // Bobot channel dibatasi kecil supaya satu channel tidak menenggelamkan yang lain.
            val chScore = tanh((w[ck] ?: 0.0) / 8.0) * 2.0
            val kwVals = kws.map { tanh((w["kw:$it"] ?: 0.0) / 5.0) }
            val kwScore = (kwVals.sortedDescending().take(3).sum() + kwVals.filter { it < 0 }.sum()) * 0.8
            val ageH = (now - e.savedAt).coerceAtLeast(0L) / 3_600_000.0
            val fresh = 0.8 * exp(-ageH / 48.0)
            val pop = if (e.views > 0) log10(e.views.toDouble() + 1.0) / 10.0 else 0.0
            val jitter = (((e.url.hashCode() xor day.toInt()) and 0x7fffffff) % 1000) / 1000.0 * 0.15
            // Jeda: channel yang baru ditonton diturunkan sedikit.
            val cooldown = minOf(1.2, 0.4 * (recentChannels[ck] ?: 0))
            val genre = e.genre ?: Genres.classify(e.title, e.channel) ?: "?"
            Scored(e, ck, genre, chScore + kwScore + fresh + pop + jitter - cooldown, kws.toSet())
        }.filter { it.score > -2.5 } // yang jelas tidak disukai disingkirkan

        // Kelompokkan per genre; di dalam kelompok, yang paling cocok di depan.
        val buckets = HashMap<String, MutableList<Scored>>()
        for (sc in scored.sortedByDescending { it.score }) buckets.getOrPut(sc.genre) { ArrayList() }.add(sc)

        var targets = genreTargets(genreProfile(history, signals, now), now)
        if (targets.isEmpty()) {
            val ids = buckets.keys.toList()
            targets = ids.associateWith { 1.0 / ids.size }
        }

        val total = minOf(60, scored.size)
        val picked = HashMap<String, Int>()
        val perChannel = HashMap<String, Int>()
        val result = ArrayList<Scored>()
        while (result.size < total) {
            val slot = result.size
            // Genre yang paling "ketinggalan" dari jatahnya dan masih punya video.
            val live = targets.filter { (buckets[it.key]?.isNotEmpty()) == true }
            val genre = live.maxByOrNull { (g, share) -> share * (slot + 1) - (picked[g] ?: 0) }?.key
                ?: buckets.entries.filter { it.value.isNotEmpty() }.maxByOrNull { it.value.first().score }?.key
                ?: break
            val bucket = buckets[genre] ?: break
            val lastChannel = result.lastOrNull()?.channel
            val recentWords = HashSet<String>()
            for (r in result.takeLast(3)) recentWords.addAll(r.kws)

            val chosen = bucket.maxByOrNull { c ->
                val overlap = minOf(3, c.kws.count { it in recentWords })
                c.score -
                    0.35 * (perChannel[c.channel] ?: 0) -
                    (if (c.channel == lastChannel) 1.0 else 0.0) -
                    0.25 * overlap
            } ?: break

            bucket.remove(chosen)
            result += chosen
            picked[genre] = (picked[genre] ?: 0) + 1
            perChannel[chosen.channel] = (perChannel[chosen.channel] ?: 0) + 1
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
