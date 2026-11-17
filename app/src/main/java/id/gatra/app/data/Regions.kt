package id.gatra.app.data

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/**
 * Lokasi YouTube: negara yang dipakai untuk rekomendasi dan pencarian.
 * [queries] = kueri bahasa setempat: 0 populer, 1 musik, 2 berita, 3 game.
 * [english] dipakai untuk kategori lain (olahraga, teknologi, dst.) bersama kata kunci Inggris.
 */
data class Region(
    val code: String,
    val name: String,
    val flag: String,
    val english: String,
    val queries: List<String>
)

object Regions {
    val all: List<Region> = listOf(
        Region("ID", "Indonesia", "\uD83C\uDDEE\uD83C\uDDE9", "Indonesia",
            listOf("video populer indonesia", "musik indonesia terbaru", "berita terkini hari ini", "gameplay seru")),
        Region("MY", "Malaysia", "\uD83C\uDDF2\uD83C\uDDFE", "Malaysia",
            listOf("video popular malaysia", "lagu melayu terbaru", "berita terkini malaysia", "gameplay menarik")),
        Region("PH", "Filipina", "\uD83C\uDDF5\uD83C\uDDED", "Philippines",
            listOf("trending video philippines", "opm bagong kanta", "balita ngayon", "gameplay tagalog")),
        Region("TH", "Thailand", "\uD83C\uDDF9\uD83C\uDDED", "Thailand",
            listOf("วิดีโอยอดนิยมไทย", "เพลงไทยใหม่ล่าสุด", "ข่าววันนี้", "เกมเพลย์สนุก")),
        Region("VN", "Vietnam", "\uD83C\uDDFB\uD83C\uDDF3", "Vietnam",
            listOf("video thịnh hành việt nam", "nhạc việt mới nhất", "tin tức hôm nay", "gameplay hay nhất")),
        Region("IN", "India", "\uD83C\uDDEE\uD83C\uDDF3", "India",
            listOf("trending videos india", "new hindi songs", "latest news india", "gaming india")),
        Region("JP", "Jepang", "\uD83C\uDDEF\uD83C\uDDF5", "Japan",
            listOf("人気動画 日本", "最新 J-POP", "今日のニュース", "ゲーム実況")),
        Region("KR", "Korea Selatan", "\uD83C\uDDF0\uD83C\uDDF7", "Korea",
            listOf("인기 동영상 한국", "최신 케이팝", "오늘의 뉴스", "게임 플레이")),
        Region("US", "Amerika Serikat", "\uD83C\uDDFA\uD83C\uDDF8", "USA",
            listOf("trending videos usa", "new music this week", "breaking news today", "gameplay highlights")),
        Region("GB", "Inggris", "\uD83C\uDDEC\uD83C\uDDE7", "UK",
            listOf("trending videos uk", "new uk music", "uk news today", "gameplay highlights uk")),
        Region("AU", "Australia", "\uD83C\uDDE6\uD83C\uDDFA", "Australia",
            listOf("trending videos australia", "new australian music", "australia news today", "gameplay australia")),
        Region("DE", "Jerman", "\uD83C\uDDE9\uD83C\uDDEA", "Germany",
            listOf("beliebte videos deutschland", "neue deutsche musik", "nachrichten heute", "gameplay deutsch")),
        Region("FR", "Prancis", "\uD83C\uDDEB\uD83C\uDDF7", "France",
            listOf("vidéos populaires france", "nouvelle musique française", "actualités du jour", "gameplay français")),
        Region("ES", "Spanyol", "\uD83C\uDDEA\uD83C\uDDF8", "Spain",
            listOf("vídeos populares españa", "música nueva españa", "noticias de hoy españa", "gameplay español")),
        Region("IT", "Italia", "\uD83C\uDDEE\uD83C\uDDF9", "Italy",
            listOf("video popolari italia", "nuova musica italiana", "notizie di oggi", "gameplay italiano")),
        Region("BR", "Brasil", "\uD83C\uDDE7\uD83C\uDDF7", "Brazil",
            listOf("vídeos populares brasil", "música nova brasil", "notícias de hoje", "gameplay brasileiro")),
        Region("MX", "Meksiko", "\uD83C\uDDF2\uD83C\uDDFD", "Mexico",
            listOf("videos populares méxico", "música nueva méxico", "noticias de hoy méxico", "gameplay mexicano")),
        Region("TR", "Turki", "\uD83C\uDDF9\uD83C\uDDF7", "Turkey",
            listOf("popüler videolar türkiye", "yeni türkçe müzik", "bugünün haberleri", "oyun videosu")),
        Region("SA", "Arab Saudi", "\uD83C\uDDF8\uD83C\uDDE6", "Saudi Arabia",
            listOf("فيديوهات رائجة السعودية", "أغاني جديدة", "أخبار اليوم", "قيمبلاي")),
        Region("RU", "Rusia", "\uD83C\uDDF7\uD83C\uDDFA", "Russia",
            listOf("популярные видео россия", "новая русская музыка", "новости сегодня", "геймплей"))
    )

    @Volatile
    var current: Region = all[0]
        private set

    /** Daerah asal: personalisasi (riwayat dan langganan) hanya memengaruhi rekomendasi di sini. */
    val isHome: Boolean get() = current.code == all[0].code

    fun byCode(code: String?): Region = all.firstOrNull { it.code == code } ?: all[0]

    /** Pasang negara aktif ke aplikasi dan ke NewPipe. Bahasa tampilan tetap Indonesia. */
    fun apply(code: String?) {
        current = byCode(code)
        NewPipe.setupLocalization(Localization("id", "ID"), ContentCountry(current.code))
    }
}
