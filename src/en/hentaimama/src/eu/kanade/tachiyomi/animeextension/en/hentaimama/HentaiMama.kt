package eu.kanade.tachiyomi.animeextension.en.hentaimama

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HentaiMama :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HentaiMama"

    override val baseUrl = "https://hentaimama.io"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)

    @Serializable
    class Source(
        val file: String,
        val label: String? = null,
        val type: String? = null,
    )

    // Popular Anime

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/hentai-series/?filter=weekly" else "$baseUrl/hentai-series/page/$page/?filter=weekly"
        val document = client.get(url).asJsoup()
        return AnimesPage(animeListFromDocument(document), hasNextPage(document))
    }
    private fun animeListFromDocument(document: Document): List<SAnime> = document.select("article.series-card").map { element ->
        SAnime.create().apply {
            setUrlWithoutDomain(element.select("a.sc-poster").attr("href"))
            title = element.select("h3.sc-title a").text()
            thumbnail_url = element.select("a.sc-poster img").attr("src")
        }
    }

    private fun hasNextPage(document: Document) = document.select("div.pagination.dt-pg a.dt-pg-next").isNotEmpty()

    // Episodes

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.get(baseUrl + anime.url).asJsoup()
        return episodeListFromDocument(document)
    }

    override fun episodeListParse(response: Response): List<SEpisode> = episodeListFromDocument(response.asJsoup())

    private fun episodeListFromDocument(document: Document): List<SEpisode> = document.select("div.dt-se-list a.dt-se-item").map { element ->
        val epNumMatch = EPISODE_NUMBER_REGEX.find(element.select(".dt-se-title").text())
        SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            name = element.select(".dt-se-title").text()
            date_upload = EPISODE_DATE_FORMAT.tryParse(element.select("span.dt-se-date").text())
            episode_number = epNumMatch?.groups?.get(1)?.value?.toFloatOrNull() ?: -1f
        }
    }.reversed()

    // Video Extractor

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.get(baseUrl + episode.url)
        val document = response.asJsoup()

        val postId = document.select("#post_report input[name=idpost]").attr("value")

        val hosters = document.select(".dt-mi-tabs a").mapNotNull { tab ->
            val optionNumber = tab.attr("href").removePrefix("#option-")
            if (optionNumber.isBlank()) return@mapNotNull null

            val serverId = tab.text()

            Hoster(
                hosterName = serverId,
                internalData = "$postId###$optionNumber###$serverId",
            )
        }

        return hosters
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_VALUES[1])
        val newList = mutableListOf<Hoster>()
        var preferred = 0
        for (hoster in this) {
            val serverId = hoster.internalData.substringAfterLast("###")

            if (serverId == preferredServer) {
                newList.add(preferred, hoster)
                preferred++
            } else {
                newList.add(hoster)
            }
        }
        return newList
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val (postId, optionNumber) = hoster.internalData.split("###")

        val body = FormBody.Builder()
            .add("action", "get_player_contents")
            .add("a", postId)
            .add("i", optionNumber)
            .build()

        val newHeaders = Headers.headersOf("referer", "$baseUrl/")
        val mirrorResponse = client.post("$baseUrl/wp-admin/admin-ajax.php", newHeaders, body)

        // Response is a JSON array of HTML fragments; this mirror's fragment
        // sits at index (optionNumber - 1).
        val optionIndex = optionNumber.toIntOrNull() ?: return emptyList()
        val fragment = mirrorResponse.parseAs<List<String>>().getOrNull(optionIndex - 1)?.takeUnless { it.isBlank() }
            ?: return emptyList()

        val iframeSrc = Jsoup.parseBodyFragment(fragment, baseUrl).selectFirst("iframe")?.attr("abs:src")
            ?: return emptyList()

        val playerBody = client.get(iframeSrc).asJsoup().body().toString()

        val sourcesJson = SOURCES_ARRAY_REGEX.find(playerBody)?.groupValues?.get(1)
            ?: return emptyList()

        val sources = sourcesJson.parseAs<List<Source>>()
        if (sources.isEmpty()) return emptyList()

        return sources.flatMap { source ->
            val file = source.file.replace("\\/", "/")
            val label = source.label
            val type = source.type

            val fallback = listOf(
                Video(
                    videoUrl = file,
                    videoTitle = if (label != null) "${hoster.hosterName} - $label" else hoster.hosterName,
                    headers = headers,
                    initialized = true,
                ),
            )

            if (type == "hls" || file.contains(".m3u8")) {
                runCatching {
                    playlistUtils.extractFromHls(
                        playlistUrl = file,
                        referer = baseUrl,
                        videoNameGen = { quality -> "${hoster.hosterName} - $quality" },
                    )
                }.getOrNull()?.takeIf { it.isNotEmpty() } ?: fallback
            } else {
                fallback
            }
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_VIDEO_QUALITY_KEY, PREF_VIDEO_QUALITY_DEFAULT)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.videoTitle.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    // Search

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val parameters = getSearchParameters(filters)
        val response = if (query.isNotEmpty()) {
            client.get("$baseUrl/page/$page/?s=${query.replace(QUERY_REGEX, " ")}")
        } else {
            client.get(if (page == 1) "$baseUrl/advance-search/?$parameters" else "$baseUrl/advance-search/page/$page/?$parameters")
        }

        val document = response.asJsoup()

        return AnimesPage(animeListFromDocument(document), hasNextPage(document))
    }

    // Details

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            thumbnail_url = document.selectFirst("div.dsc-poster img")?.attr("src")
            title = document.select("h1.dsc-title").text()
            genre = document.select("div.dsc-genres a").joinToString(", ") { it.text() }
            description = document.select("div.dsc-desc p").text()
            author = document.select("div.dsc-stats div.dsc-stat")
                .firstOrNull { it.select("span").text() == "Studio" }
                ?.select("b")?.text()
                ?.takeUnless { it.isBlank() || it == "\u2014" }
            status = parseStatus(document)
        }
    }

    private fun parseStatus(document: Document): Int = if (document.select("span.dsc-chip.is-airing").isNotEmpty()) SAnime.ONGOING else SAnime.COMPLETED

    // Latest

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/hentai-series/?filter=recent" else "$baseUrl/hentai-series/page/$page/?filter=recent"
        val document = client.get(url).asJsoup()
        return AnimesPage(animeListFromDocument(document), hasNextPage(document))
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue("mi-2")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val preferredQualityPref = ListPreference(screen.context).apply {
            key = PREF_VIDEO_QUALITY_KEY
            title = PREF_VIDEO_QUALITY_TITLE
            entries = PREF_VIDEO_QUALITY_ENTRIES
            entryValues = PREF_VIDEO_QUALITY_VALUES
            setDefaultValue(PREF_VIDEO_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(preferredQualityPref)
        screen.addPreference(videoQualityPref)
    }

    // Filters

    internal class Genre(val id: String) : AnimeFilter.CheckBox(id)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Genre", genres)
    private fun getGenres() = listOf(
        Genre("3D"),
        Genre("Action"),
        Genre("Adventure"),
        Genre("Ahegao"),
        Genre("Anal"),
        Genre("Animal Ears"),
        Genre("Beastiality"),
        Genre("Blackmail"),
        Genre("Blowjob"),
        Genre("Bondage"),
        Genre("Brainwashed"),
        Genre("Bukakke"),
        Genre("Cat Girl"),
        Genre("Comedy"),
        Genre("Cosplay"),
        Genre("Creampie"),
        Genre("Cross-dressing"),
        Genre("Dark Skin"),
        Genre("DeepThroat"),
        Genre("Demons"),
        Genre("Doctor"),
        Genre("Double Penatration"),
        Genre("Drama"),
        Genre("Dubbed"),
        Genre("Ecchi"),
        Genre("Elf"),
        Genre("Eroge"),
        Genre("Facesitting"),
        Genre("Facial"),
        Genre("Fantasy"),
        Genre("Female Doctor"),
        Genre("Female Teacher"),
        Genre("Femdom"),
        Genre("Footjob"),
        Genre("Futanari"),
        Genre("Gangbang"),
        Genre("Gore"),
        Genre("Gyaru"),
        Genre("Harem"),
        Genre("Historical"),
        Genre("Horny Slut"),
        Genre("Housewife"),
        Genre("Humiliation"),
        Genre("Incest"),
        Genre("Inflation"),
        Genre("Internal Cumshot"),
        Genre("Lactation"),
        Genre("Large Breasts"),
        Genre("Lolicon"),
        Genre("Magical Girls"),
        Genre("Maid"),
        Genre("Martial Arts"),
        Genre("Megane"),
        Genre("MILF"),
        Genre("Mind Break"),
        Genre("Molestation"),
        Genre("Non-Japanese"),
        Genre("NTR"),
        Genre("Nuns"),
        Genre("Nurses"),
        Genre("Office Ladies"),
        Genre("Police"),
        Genre("POV"),
        Genre("Pregnant"),
        Genre("Princess"),
        Genre("Public Sex"),
        Genre("Rape"),
        Genre("Rim job"),
        Genre("Romance"),
        Genre("Scat"),
        Genre("School Girls"),
        Genre("Sci-Fi"),
        Genre("Shimapan"),
        Genre("Short"),
        Genre("Shoutacon"),
        Genre("Slaves"),
        Genre("Sports"),
        Genre("Squirting"),
        Genre("Stocking"),
        Genre("Strap-on"),
        Genre("Strapped On"),
        Genre("Succubus"),
        Genre("Super Power"),
        Genre("Supernatural"),
        Genre("Swimsuit"),
        Genre("Tentacles"),
        Genre("Three some"),
        Genre("Tits Fuck"),
        Genre("Torture"),
        Genre("Toys"),
        Genre("Train Molestation"),
        Genre("Tsundere"),
        Genre("Uncensored"),
        Genre("Urination"),
        Genre("Vampire"),
        Genre("Vanilla"),
        Genre("Virgins"),
        Genre("Widow"),
        Genre("X-Ray"),
        Genre("Yuri"),
    )

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(years: List<Year>) : AnimeFilter.Group<Year>("Year", years)
    private fun getYears(): List<Year> {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return (currentYear downTo 1987).map { Year(it.toString()) }
    }
    internal class Producer(val id: String) : AnimeFilter.CheckBox(id)
    private class ProducerList(producers: List<Producer>) : AnimeFilter.Group<Producer>("Producer", producers)
    private fun getProducer() = listOf(
        Producer("8bit"),
        Producer("Actas"),
        Producer("Active"),
        Producer("AIC"),
        Producer("AIC A.S.T.A."),
        Producer("Alice Soft"),
        Producer("An DerCen"),
        Producer("Angelfish"),
        Producer("Animac"),
        Producer("AniMan"),
        Producer("Animax"),
        Producer("Antechinus"),
        Producer("APPP"),
        Producer("Armor"),
        Producer("Arms"),
        Producer("Asahi Production"),
        Producer("AT-2"),
        Producer("Blue Eyes"),
        Producer("BOMB! CUTE! BOMB!"),
        Producer("BOOTLEG"),
        Producer("Bunnywalker"),
        Producer("Central Park Media"),
        Producer("CherryLips"),
        Producer("ChiChinoya"),
        Producer("Chippai"),
        Producer("ChuChu"),
        Producer("Circle Tribute"),
        Producer("CLOCKUP"),
        Producer("Collaboration Works"),
        Producer("Comic Media"),
        Producer("Cosmic Ray"),
        Producer("Cosmo"),
        Producer("Cotton Doll"),
        Producer("Cranberry"),
        Producer("D3"),
        Producer("Daiei"),
        Producer("Digital Works"),
        Producer("Discovery"),
        Producer("Dream Force"),
        Producer("Dubbed"),
        Producer("Easy Film"),
        Producer("Echo"),
        Producer("EDGE"),
        Producer("Filmlink International"),
        Producer("Five Ways"),
        Producer("Front Line"),
        Producer("Frontier Works"),
        Producer("Godoy"),
        Producer("Gold Bear"),
        Producer("Green Bunny"),
        Producer("Himajin Planning"),
        Producer("Hokiboshi"),
        Producer("Hoods Entertainment"),
        Producer("Horipro"),
        Producer("Hot Bear"),
        Producer("HydraFXX"),
        Producer("Innocent Grey"),
        Producer("Jam"),
        Producer("JapanAnime"),
        Producer("King Bee"),
        Producer("Kitty Films"),
        Producer("Kitty Media"),
        Producer("Knack Productions"),
        Producer("KSS"),
        Producer("Lemon Heart"),
        Producer("Lune Pictures"),
        Producer("Majin"),
        Producer("Marvelous Entertainment"),
        Producer("Mary Jane"),
        Producer("Media"),
        Producer("Media Blasters"),
        Producer("Milkshake"),
        Producer("Mitsu"),
        Producer("Moonstone Cherry"),
        Producer("Mousou Senka"),
        Producer("MS Pictures"),
        Producer("Nihikime no Dozeu"),
        Producer("Nur"),
        Producer("NuTech Digital"),
        Producer("Obtain Future"),
        Producer("Office Take Off"),
        Producer("OLE-M"),
        Producer("Oriental Light and Magic"),
        Producer("Oz"),
        Producer("Pashmina"),
        Producer("Pink Pineapple"),
        Producer("Pixy"),
        Producer("PoRO"),
        Producer("Production I.G"),
        Producer("Queen Bee"),
        Producer("Sakura Purin Animation"),
        Producer("Schoolzone"),
        Producer("Selfish"),
        Producer("Seven"),
        Producer("Shelf"),
        Producer("Shinkuukan"),
        Producer("Shinyusha"),
        Producer("Shouten"),
        Producer("Silky’s"),
        Producer("Soft Garage"),
        Producer("SoftCel Pictures"),
        Producer("SPEED"),
        Producer("Studio 9 Maiami"),
        Producer("Studio Eromatick"),
        Producer("Studio Fantasia"),
        Producer("Studio Jack"),
        Producer("Studio Kyuuma"),
        Producer("Studio Matrix"),
        Producer("Studio Sign"),
        Producer("Studio Tulip"),
        Producer("Studio Unicorn"),
        Producer("Suzuki Mirano"),
        Producer("T-Rex"),
        Producer("The Right Stuf International"),
        Producer("Toho Company"),
        Producer("Top-Marschal"),
        Producer("Toranoana"),
        Producer("Toshiba Entertainment"),
        Producer("Triangle Bitter"),
        Producer("Triple X"),
        Producer("Union Cho"),
        Producer("Valkyria"),
        Producer("White Bear"),
        Producer("Y.O.U.C"),
        Producer("ZIZ Entertainment"),
        Producer("Zyc"),

    )

    private data class Order(val name: String, val id: String)
    private class OrderList(Orders: Array<String>) : AnimeFilter.Select<String>("Order", Orders)
    private val orderName = getOrder().map {
        it.name
    }.toTypedArray()
    private fun getOrder() = listOf(
        Order("Weekly Views", "weekly"),
        Order("Monthly Views", "monthly"),
        Order("Alltime Views", "alltime"),
        Order("A-Z", "alphabet"),
        Order("Rating", "rating"),

    )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        var totalstring = ""
        var sortBy = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreList -> { // ---Genre
                    filter.state.forEach { Genre ->
                        if (Genre.state) {
                            totalstring =
                                totalstring + "&genres_filter%5B" + "%5D=" + Genre.id
                        }
                    }
                }

                is YearList -> { // ---Year
                    filter.state.forEach { Year ->
                        if (Year.state) {
                            totalstring =
                                totalstring + "&years_filter%5B" + "%5D=" + Year.id
                        }
                    }
                }

                is ProducerList -> { // ---Producer
                    filter.state.forEach { Producer ->
                        if (Producer.state) {
                            totalstring =
                                totalstring + "&studios_filter%5B" + "%5D=" + Producer.id
                        }
                    }
                }

                is OrderList -> { // ---Order
                    sortBy = getOrder()[filter.state].id
                }

                else -> {}
            }
        }

        return "$totalstring&submit=Submit&filter=$sortBy"
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Ignored if using Text Search"),
        AnimeFilter.Separator(),
        OrderList(orderName),
        GenreList(getGenres()),
        YearList(getYears()),
        ProducerList(getProducer()),
    )

    companion object {
        private const val PREF_VIDEO_QUALITY_KEY = "preferred_video_quality"
        private const val PREF_VIDEO_QUALITY_TITLE = "Preferred quality"
        private val PREF_VIDEO_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p")
        private val PREF_VIDEO_QUALITY_VALUES = arrayOf("1080p", "720p", "480p")
        private const val PREF_VIDEO_QUALITY_DEFAULT = "1080p"
        private const val PREF_SERVER_KEY = "preferred_video_server"
        private const val PREF_SERVER_TITLE = "Preferred video server"
        private val PREF_SERVER_ENTRIES = arrayOf("Mirror 1", "Mirror 2", "Mirror 3")
        private val PREF_SERVER_VALUES = arrayOf("mi-1", "mi-2", "mi-3")
        private val EPISODE_NUMBER_REGEX = Regex("Episode (\\d+\\.?\\d*)")
        private val EPISODE_DATE_FORMAT = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        private val SOURCES_ARRAY_REGEX = Regex("sources:\\s*(\\[.+?\\])", RegexOption.DOT_MATCHES_ALL)
        private val QUERY_REGEX = Regex("[\\W]")
    }
}
