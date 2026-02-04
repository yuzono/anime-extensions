package eu.kanade.tachiyomi.animeextension.ar.animerco

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.animerco.extractors.SharedExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import keiyoushi.utils.getPreferencesLazy
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.math.roundToInt

class Animerco :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Animerco"

    override val baseUrl = "https://web.animerco.org"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/trending/page/$page/")

    override fun popularAnimeSelector() = "div.media-block > div > a.image"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.attr("data-src")
        title = element.attr("title")
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination li:last-child a:has(svg)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page/?s=")
    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val builder = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("page")
            addPathSegment(page.toString())
            addPathSegment("")
            addQueryParameter("s", query)
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        if (filter.toUriPart().isNotBlank()) {
                            addQueryParameter("genres", filter.toUriPart())
                        }
                    }

                    is YearFilter -> {
                        if (filter.state.isNotBlank()) {
                            addQueryParameter("dtyear", filter.state)
                        }
                    }

                    else -> {}
                }
            }
        }
        return GET(builder.build())
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.selectFirst("a.poster")?.run {
            thumbnail_url = attr("data-src")
            title = attr("title").ifEmpty {
                document.selectFirst("div.media-title h1")!!.text()
            }
        }

        val infosDiv = document.selectFirst("ul.media-info")
        author = infosDiv?.select("li:contains(الشبكات) a")
            ?.eachText()
            ?.joinToString()
            ?.takeIf(String::isNotBlank)
        artist = infosDiv?.select("li:contains(الأستوديو) a")
            ?.eachText()
            ?.joinToString()
            ?.takeIf(String::isNotBlank)
        genre = document.select("nav.Nvgnrs a, ul.media-info li:contains(النوع) a")
            .eachText()
            .joinToString()

        description = buildString {
            document.selectFirst(".media-rating .score")?.let {
                append(fancyScore(it.text()))
            }
            document.selectFirst("div.media-story p")?.also {
                append(it.text())
            }
            document.selectFirst("div.media-title > h3.alt-title")?.also {
                append("\n\nAlternative title: " + it.text())
            }
        }

        status = document.select("ul.chapters-list a.se-title > span.badge")
            .eachText()
            .let { items ->
                when {
                    items.all { it.contains("مكتمل") } -> SAnime.COMPLETED
                    items.any { it.contains("يعرض الأن") } -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
            }
    }

    private fun fancyScore(score: String): String = score.toFloatOrNull()?.div(2f)
        ?.roundToInt()
        ?.let {
            buildString {
                append("★".repeat(it))
                if (it < 5) append("☆".repeat(5 - it))
                append(" $score\n")
            }
        } ?: ""

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul.episodes-lists li a:has(h3)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        if (document.location().contains("/movies/")) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(document.location())
                    episode_number = 1F
                    name = "فيلم" // Movie
                },
            )
        }

        return document.select(episodeListSelector()).flatMap { el ->
            val doc = client.newCall(GET(el.attr("abs:href"), headers)).execute()
                .asJsoup()
            val seasonName = doc.selectFirst("div.media-title h1")?.text() ?: "Season"
            val seasonNum = seasonName.substringAfterLast(" ").toIntOrNull() ?: 1
            doc.select(episodeListSelector()).map {
                episodeFromElement(it, seasonName, seasonNum)
            }.reversed()
        }.reversed()
    }

    private fun episodeFromElement(element: Element, seasonName: String, seasonNum: Int) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val epText = element.selectFirst("h3")?.ownText() ?: "Episode"
        name = "$epText - $seasonName"
        val epNum = epText.filter(Char::isDigit)
        // good luck trying to track this xD
        episode_number = "$seasonNum.${epNum.padStart(3, '0')}".toFloatOrNull() ?: 1F
    }

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select(videoListSelector())
        return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    override fun videoListSelector() = "ul.server-list > li > a"

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val gdrivePlayerExtractor by lazy { GdrivePlayerExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val sharedExtractor by lazy { SharedExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }

    private fun getPlayerVideos(player: Element): List<Video> {
        val url = getPlayerUrl(player) ?: return emptyList()
        val name = player.selectFirst("span.server")?.text()?.lowercase() ?: "Unknown"
        return when {
            "ok.ru" in url -> okruExtractor.videosFromUrl(url)

            "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url, headers)

            "wish" in name -> streamWishExtractor.videosFromUrl(url)

            "yourupload" in url -> yourUploadExtractor.videoFromUrl(url, headers)

            "dood" in url -> doodExtractor.videoFromUrl(url)?.let(::listOf)

            "drive.google" in url -> {
                val newUrl = "https://gdriveplayer.to/embed2.php?link=$url"
                gdrivePlayerExtractor.videosFromUrl(newUrl, "GdrivePlayer", headers)
            }

            "streamtape" in url -> streamTapeExtractor.videoFromUrl(url)?.let(::listOf)

            "4shared" in url -> sharedExtractor.videoFromUrl(url)?.let(::listOf)

            "uqload" in url -> uqloadExtractor.videosFromUrl(url)

            VIDBOM_DOMAINS.any(url::contains) -> vidBomExtractor.videosFromUrl(url)

            else -> null
        } ?: emptyList()
    }

    private fun getPlayerUrl(player: Element): String? {
        val body = FormBody.Builder()
            .add("action", "player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute()
            .use { response ->
                response.body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
                    .takeIf(String::isNotBlank)
            }
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        GenreFilter(genresList),
        YearFilter(),
    )

    private class GenreFilter(val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>("التصنيفات", vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    class YearFilter : AnimeFilter.Text("السنوات") // Years

    private val genresList = arrayOf(
        "التصنيفات" to "",
        "أكشن" to "action",
        "أوفا" to "ova",
        "إثارة" to "thriller",
        "إيتشي" to "ecchi",
        "السفر عبر الزمن" to "time-travel",
        "بوليسي" to "police",
        "تاريخي" to "historical",
        "تحقيقات" to "detective",
        "تشويق" to "suspense",
        "جريمة" to "crime",
        "جنون" to "dementia",
        "جوسي" to "josei",
        "حريم" to "harem",
        "حياة العمل" to "work-life",
        "خارق للطبيعة" to "supernatural",
        "خيال" to "fantasy",
        "خيال علمي" to "science-fiction",
        "خيال علمي وفانتازيا" to "sci-fi-fantasy",
        "دراما" to "drama",
        "دموي" to "gore",
        "ذواق" to "gourmet",
        "رعب" to "horror",
        "رومانسي" to "romance",
        "رياضي" to "sports",
        "ساخر" to "parody",
        "ساموراي" to "samurai",
        "سباق" to "racing",
        "سحر" to "magic",
        "سينين" to "seinen",
        "شريحة من الحياة" to "slice-of-life",
        "شوجو" to "shoujo",
        "شونين" to "shounen",
        "شونين آي" to "shounen-ai",
        "شياطين" to "demons",
        "طبي" to "medical",
        "طليعية" to "avant-garde",
        "عسكري" to "military",
        "غموض" to "mystery",
        "فضاء" to "space",
        "فنون تعبيرية" to "performing-arts",
        "فنون تمثيلية" to "performing-arts-2",
        "فنون قتالية" to "martial-arts",
        "قوة خارقة" to "super-power",
        "كوميدي" to "comedy",
        "لعبة" to "game",
        "لعبة استراتيجية" to "strategy-game",
        "مدرسي" to "school",
        "مصاصي دماء" to "vampire",
        "مغامرة" to "adventure",
        "موسيقي" to "music",
        "ميثولوجيا" to "mythology",
        "ميكا" to "mecha",
        "نفسي" to "psychological",
    )

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "Doodstream", "StreamTape")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360", "Doodstream", "StreamTape")

        private val VIDBOM_DOMAINS = listOf(
            "vidbom.com", "vidbem.com", "vidbm.com", "vedpom.com",
            "vedbom.com", "vedbom.org", "vadbom.com",
            "vidbam.org", "myviid.com", "myviid.net",
            "myvid.com", "vidshare.com", "vedsharr.com",
            "vedshar.com", "vedshare.com", "vadshar.com", "vidshar.org",
        )
    }
}
