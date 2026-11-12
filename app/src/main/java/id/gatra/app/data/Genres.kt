package id.gatra.app.data

/**
 * Genre untuk rekomendasi "Untukmu". YouTube tidak memberi label genre pada hasil pencarian,
 * jadi genre dikenali dari kata di judul/nama channel, atau dari kueri pencarian yang menemukan videonya.
 * [near] = genre "bertetangga": sering disukai orang yang menyukai genre ini.
 */
object Genres {
    class Genre(
        val id: String,
        val label: String,
        val queries: List<String>,
        val words: List<String>,
        val near: List<String>
    )

    val all: List<Genre> = listOf(
        Genre(
            "horor", "Horor",
            listOf("cerita horor terbaru", "kisah horor nyata", "uji nyali tempat angker"),
            listOf(
                "horor", "horror", "hantu", "seram", "angker", "kuntilanak", "pocong", "tuyul", "santet",
                "genderuwo", "mistis", "paranormal", "ghost", "haunted", "scary", "creepy", "uji nyali",
                "cerita horor", "pesugihan", "kesurupan"
            ),
            listOf("misteri", "film", "anime")
        ),
        Genre(
            "misteri", "Misteri",
            listOf("misteri dunia terbaru", "true crime indonesia", "kasus misterius belum terpecahkan"),
            listOf(
                "misteri", "mystery", "kriminal", "pembunuhan", "detektif", "investigasi", "konspirasi",
                "true crime", "kasus pembunuhan", "kasus misterius", "teori konspirasi", "terungkap"
            ),
            listOf("horor", "film", "edukasi")
        ),
        Genre(
            "musik", "Musik",
            listOf("musik indonesia terbaru", "lagu pop terbaru", "cover lagu akustik"),
            listOf(
                "lagu", "musik", "music", "lirik", "lyrics", "konser", "akustik", "acoustic", "remix",
                "cover", "official audio", "official music video", "playlist lagu", "dangdut", "kpop"
            ),
            listOf("film", "komedi", "gaya")
        ),
        Genre(
            "game", "Game",
            listOf("gameplay seru", "game mobile terbaik", "walkthrough game"),
            listOf(
                "game", "gaming", "gameplay", "walkthrough", "minecraft", "roblox", "pubg", "valorant",
                "esports", "mobile legends", "free fire", "genshin", "gamer", "speedrun"
            ),
            listOf("anime", "teknologi", "komedi")
        ),
        Genre(
            "berita", "Berita",
            listOf("berita terkini hari ini", "berita terbaru indonesia", "breaking news"),
            listOf(
                "berita", "news", "breaking", "politik", "presiden", "pemerintah", "dpr", "pemilu",
                "banjir", "gempa", "kabar terbaru", "update terkini"
            ),
            listOf("bisnis", "edukasi", "olahraga")
        ),
        Genre(
            "teknologi", "Teknologi",
            listOf("review teknologi terbaru", "review hp terbaru", "tips gadget dan laptop"),
            listOf(
                "teknologi", "technology", "gadget", "smartphone", "iphone", "android", "laptop", "unboxing",
                "tech", "samsung", "xiaomi", "processor", "komputer", "software", "chatgpt"
            ),
            listOf("game", "otomotif", "bisnis")
        ),
        Genre(
            "olahraga", "Olahraga",
            listOf("highlight sepak bola terbaru", "badminton terbaru", "motogp dan f1 terbaru"),
            listOf(
                "sepak", "bola", "liga", "gol", "football", "soccer", "badminton", "basket", "motogp",
                "olahraga", "timnas", "tinju", "ufc", "voli", "pertandingan", "highlights"
            ),
            listOf("berita", "otomotif", "komedi")
        ),
        Genre(
            "film", "Film & Serial",
            listOf("trailer film terbaru", "alur cerita film", "review serial terbaru"),
            listOf(
                "film", "movie", "trailer", "serial", "drama", "sinopsis", "alur cerita", "bioskop",
                "netflix", "sinema", "episode", "series"
            ),
            listOf("anime", "horor", "musik")
        ),
        Genre(
            "anime", "Anime",
            listOf("anime terbaru", "rekomendasi anime", "alur cerita anime"),
            listOf(
                "anime", "manga", "naruto", "one piece", "donghua", "isekai", "otaku", "jujutsu",
                "demon slayer", "dragon ball"
            ),
            listOf("game", "film", "komedi")
        ),
        Genre(
            "kuliner", "Kuliner",
            listOf("resep masakan sederhana", "kuliner jajanan terbaru", "street food indonesia"),
            listOf(
                "resep", "masak", "masakan", "makanan", "kuliner", "food", "cooking", "jajanan", "mukbang",
                "street food", "restoran", "kue", "cemilan"
            ),
            listOf("gaya", "komedi", "edukasi")
        ),
        Genre(
            "komedi", "Komedi",
            listOf("komedi lucu terbaru", "stand up comedy indonesia", "video lucu kocak"),
            listOf(
                "lucu", "komedi", "comedy", "prank", "humor", "funny", "meme", "kocak", "stand up",
                "standup", "parodi", "ngakak"
            ),
            listOf("film", "musik", "gaya")
        ),
        Genre(
            "edukasi", "Edukasi",
            listOf("fakta menarik sejarah", "belajar sains dan pengetahuan", "tutorial belajar"),
            listOf(
                "belajar", "tutorial", "sejarah", "sains", "science", "edukasi", "fakta", "pengetahuan",
                "dokumenter", "documentary", "penjelasan", "explained", "kuliah"
            ),
            listOf("teknologi", "berita", "misteri")
        ),
        Genre(
            "otomotif", "Otomotif",
            listOf("review mobil terbaru", "modifikasi motor", "tips otomotif"),
            listOf(
                "mobil", "motor", "otomotif", "modifikasi", "automotive", "supercar", "sepeda motor",
                "touring", "offroad", "truk", "bengkel"
            ),
            listOf("teknologi", "olahraga", "gaya")
        ),
        Genre(
            "religi", "Religi",
            listOf("kajian islam terbaru", "ceramah singkat", "sholawat terbaru"),
            listOf(
                "ceramah", "kajian", "islam", "quran", "doa", "sholawat", "khutbah", "ustaz", "ustadz",
                "dakwah", "hadits", "tausiyah", "murottal"
            ),
            listOf("edukasi", "berita", "gaya")
        ),
        Genre(
            "gaya", "Gaya Hidup",
            listOf("vlog harian terbaru", "travel vlog indonesia", "tips gaya hidup"),
            listOf(
                "vlog", "travel", "liburan", "wisata", "daily", "gaya hidup", "lifestyle", "traveling",
                "staycation", "outfit", "skincare"
            ),
            listOf("kuliner", "musik", "komedi")
        ),
        Genre(
            "bisnis", "Bisnis",
            listOf("tips bisnis dan investasi", "cara memulai usaha", "saham dan crypto terbaru"),
            listOf(
                "bisnis", "business", "saham", "investasi", "crypto", "kripto", "bitcoin", "keuangan",
                "usaha", "ekonomi", "finansial", "trading", "startup"
            ),
            listOf("berita", "teknologi", "edukasi")
        )
    )

    private val byIdMap: Map<String, Genre> = all.associateBy { it.id }
    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    fun byId(id: String): Genre? = byIdMap[id]

    fun label(id: String): String = byIdMap[id]?.label ?: "Lainnya"

    fun pickQuery(g: Genre, day: Int, offset: Int): String = g.queries[(day + offset).mod(g.queries.size)]

    /** Tebak genre dari judul dan nama channel. Null bila tidak ada petunjuk yang cukup kuat. */
    fun classify(title: String, channel: String): String? {
        val t = title.lowercase()
        val titleTokens = t.split(splitter).filter { it.length >= 3 }
        val channelTokens = channel.lowercase().split(splitter).filter { it.length >= 3 }
        var best: String? = null
        var bestScore = 0
        for (g in all) {
            var s = 0
            for (w in g.words) {
                if (w.contains(' ')) {
                    if (t.contains(w)) s += 2
                } else {
                    if (titleTokens.any { it == w || (w.length >= 5 && it.startsWith(w)) }) s += 2
                    if (channelTokens.any { it == w }) s += 1
                }
            }
            if (s > bestScore) {
                bestScore = s
                best = g.id
            }
        }
        return if (bestScore >= 2) best else null
    }
}
