package eu.kanade.tachiyomi.animeextension.ru.animevost

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.text.Regex
import kotlin.text.toRegex

data class AnimeDescription(
    val year: String? = null,
    val type: String? = null,
    val rating: Int? = null,
    val votes: Int? = null,
    val description: String? = null,
)

class AnimevostSource(override val name: String, override val baseUrl: String) :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {
    private enum class SortBy(val by: String) {
        RATING("rating"),
        DATE("date"),
        NEWS_READ("news_read"),
        COMM_NUM("comm_num"),
        TITLE("title"),
    }

    private enum class SortDirection(val direction: String) {
        ASC("asc"),
        DESC("desc"),
    }

    private val preferences by getPreferencesLazy()

    override val lang = "ru"

    override val supportsLatest = true

    private val nextPageSelector = "span.nav_ext + a, td.block_4 span:not(.nav_ext) + a"

    // Helper to extract thumbnail from a given element
    private fun extractThumbnail(from: Element): String? {
        // 1) direct img inside
        val img = from.selectFirst("img")
        var src = img?.attr("src")?.takeIf { it.isNotEmpty() } ?: img?.attr("data-src")
        if (src.isNullOrEmpty()) {
            // 2) background-image in style
            val style = from.attr("style")
            val m = Regex("background-image:\\s*url\\(([^)]+)\\)").find(style)
            if (m != null) {
                src = m.groupValues[1].trim().trim('"')
            }
        }
        if (!src.isNullOrEmpty()) {
            return when {
                src.startsWith("http") -> src
                src.startsWith("/") -> baseUrl.trimEnd('/') + src
                else -> baseUrl.trimEnd('/') + "/" + src
            }
        }
        return null
    }

    private fun animeRequest(page: Int, sortBy: SortBy, sortDirection: SortDirection = SortDirection.DESC, genre: String = "all"): Request {
        val url = baseUrl.toHttpUrlOrNull()!!.newBuilder()

        var body = FormBody.Builder()
            .add("dlenewssortby", sortBy.by)
            .add("dledirection", sortDirection.direction)

        body = if (genre != "all") {
            url.addPathSegment("zhanr")
            url.addPathSegment(genre)
            body.add("set_new_sort", "dle_sort_cat")
                .add("set_direction_sort", "dle_direction_cat")
        } else {
            body.add("set_new_sort", "dle_sort_main")
                .add("set_direction_sort", "dle_direction_main")
        }

        url.addPathSegment("page")
        url.addPathSegment("$page")

        return POST(url.toString(), headers, body.build())
    }

    // Anime details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        // Isolate the main story content block to avoid pulling in user comments.
        // DLE (animevost engine) uses different class names depending on version/theme.
        // Clone so we can strip comment nodes without mutating the live document.
        val contentBlock = document.selectFirst(
            ".shortstoryContent, .full_story, .fullstory, .shortstory, #dle-content",
        )?.clone()

        // Strip DLE comment sub-trees injected after the description.
        contentBlock?.select(
            "#comments, .comments, .comment_list, .comment_block, " +
                ".zcomment, #zcomment, [class*=comment], [id*=comment]",
        )?.remove()

        // Thumbnail
        val img = document.selectFirst("img[src*='/uploads/']")
        if (img != null) {
            val src = img.attr("src")
            anime.thumbnail_url = if (src.startsWith("http")) {
                src
            } else {
                "${baseUrl.trimEnd('/')}/${src.removePrefix("/")}"
            }
        }

        // Title
        anime.title = document.selectFirst("h1, .title, .shortstoryHead h1")?.text() ?: document.title()

        // Use only the sanitised content block — never the whole document — so that
        // user comments cannot bleed into the description or the metadata fields.
        val contentText = contentBlock?.text() ?: ""

        // Extract fields from text
        val yearRegex = "Год выхода:\\s*(.+?)(?=Тип:|Жанр:|$)".toRegex()
        val genreRegex = "Жанр:\\s*(.+?)(?=Тип:|Год выхода:|$)".toRegex()
        val typeRegex = "Тип:\\s*(.+?)(?=Жанр:|Год выхода:|$)".toRegex()

        var year = ""
        var genre = ""
        var type = ""

        yearRegex.find(contentText)?.let { year = it.groupValues[1].trim() }
        genreRegex.find(contentText)?.let { genre = it.groupValues[1].trim() }
        typeRegex.find(contentText)?.let { type = it.groupValues[1].trim() }

        // Rating — comes from its own dedicated element, not the content block
        val ratingText = document.selectFirst(".current-rating, .rating")?.text() ?: ""
        val rating = try {
            ratingText.toInt()
        } catch (_: Exception) {
            0
        }

        // Strip "Поле: Значение" metadata lines so they don't duplicate
        // the structured fields already formatted by formatDescription.
        val metaLineRegex = """[А-Яа-яЁё][А-Яа-яЁё ]+:\s*[^\n]+""".toRegex()
        val pureDescription = contentText
            .replace(metaLineRegex, "")
            .replace("""\s{2,}""".toRegex(), " ")
            .trim()

        anime.genre = genre
        anime.description = formatDescription(
            AnimeDescription(
                year.ifEmpty { null },
                type.ifEmpty { null },
                rating.takeIf { it > 0 },
                null,
                pureDescription.ifEmpty { null },
            ),
        )
        return anime
    }

    private fun formatDescription(animeData: AnimeDescription): String {
        var description = ""

        if (animeData.year != null) {
            description += "Год: ${animeData.year}\n"
        }

        if (animeData.rating != null && animeData.votes != null) {
            val ratingValue = animeData.rating!!
            val stars = 5 * ratingValue / 100
            val fullStars = "★".repeat(stars)
            val emptyStars = "☆".repeat((5 - stars).coerceAtLeast(0))

            description += "Рейтинг: $fullStars$emptyStars (Голосов: ${animeData.votes})\n"
        }

        if (animeData.type != null) {
            description += "Тип: ${animeData.type}\n"
        }

        if (description.isNotEmpty()) {
            description += "\n"
        }

        val body = animeData.description?.replace("<br />", "") ?: ""
        description += body
        return description
    }

    // Episode

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val startMarker = "var data = {"
        val endMarker = "};"

        val script = document.select("script").find { it.html().contains(startMarker) }
            ?: return emptyList()

        val scriptContent = script.html()
        val dataString = scriptContent
            .substringAfter(startMarker, "")
            .substringBefore(endMarker, "")
            .takeIf { it.isNotEmpty() } ?: return emptyList()

        val cleanedDataString = dataString.trimEnd().removeSuffix(",")

        val json = Json { isLenient = true }
        val episodeData = try {
            json.decodeFromString<Map<String, String>>("{$cleanedDataString}")
        } catch (_: SerializationException) {
            return emptyList()
        }

        val episodeList = mutableListOf<SEpisode>()
        episodeData.entries.forEachIndexed { index, entry ->
            val name = entry.key
            val id = entry.value

            if (name.isNotEmpty() && id.isNotEmpty()) {
                episodeList.add(
                    SEpisode.create().apply {
                        url = "/frame5.php?play=$id&old=1"
                        this.name = name
                        episode_number = (index + 1).toFloat()
                    },
                )
            }
        }

        return episodeList.reversed()
    }

    // Latest

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeList(response)

    override fun latestUpdatesRequest(page: Int) = animeRequest(page, SortBy.DATE)

    override fun latestUpdatesSelector() = "a[href*='/tip/']"

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = nextPageSelector

    // Popular Anime

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeList(response)

    override fun popularAnimeRequest(page: Int) = animeRequest(page, SortBy.RATING)

    override fun popularAnimeSelector() = "a[href*='/tip/']"

    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun popularAnimeNextPageSelector() = nextPageSelector

    // Search

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val searchStart = if (page <= 1) 0 else page
            val resultFrom = (page - 1) * 10 + 1
            val headers: Headers =
                Headers.headersOf("Content-Type", "application/x-www-form-urlencoded", "charset", "UTF-8")
            val body = FormBody.Builder()
                .add("do", "search")
                .add("subaction", "search")
                .add("search_start", searchStart.toString())
                .add("full_search", "0")
                .add("result_from", resultFrom.toString())
                .add("story", query)
                .build()

            return POST("$baseUrl/index.php?do=search", headers, body)
        } else {
            var sortBy = SortBy.DATE
            var sortDirection = SortDirection.DESC
            var genre = "all"

            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        genre = filter.toString()
                    }

                    is SortFilter -> {
                        if (filter.state != null) {
                            sortBy = sortableList[filter.state!!.index].second

                            sortDirection = if (filter.state!!.ascending) SortDirection.ASC else SortDirection.DESC
                        }
                    }

                    else -> {}
                }
            }

            return animeRequest(page, sortBy, sortDirection, genre)
        }
    }

    // Required by ParsedAnimeHttpSource but unused — searchAnimeParse() is fully overridden.
    override fun searchAnimeSelector() = throw UnsupportedOperationException()
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector(): String? = throw UnsupportedOperationException()

    // Common anime list parser
    private fun parseAnimeList(response: Response): AnimesPage {
        val document = response.asJsoup()
        val seenUrls = mutableSetOf<String>()
        val animes = mutableListOf<SAnime>()

        // DLE cards are always div.shortstory; fall back to div.post / article if the
        // theme uses different markup.  We deliberately avoid broad selectors like
        // .content or div:has(...) because they match parent wrappers and cause
        // duplicates and wrong thumbnail lookups.
        val containers = document.select("div.shortstory, div.post, article")
            .ifEmpty {
                // Last-resort fallback: any div that directly wraps a /tip/ link
                document.select("a[href*='/tip/']")
                    .mapNotNull { it.parent() }
                    .distinctBy { it.cssSelector() }
            }

        containers.forEach { container ->
            // Canonical URL comes from the first /tip/ link inside the card
            val link = container.selectFirst("a[href*='/tip/']") ?: return@forEach
            val href = link.attr("abs:href").ifEmpty { link.attr("href") }
            if (href.isEmpty() || !seenUrls.add(href)) return@forEach

            val anime = SAnime.create()
            anime.setUrlWithoutDomain(href)

            // Title: prefer the link title-attribute or img alt (set by the site),
            // then any heading text, then the link text itself.
            val imgInLink = link.selectFirst("img")
            anime.title = link.attr("title")
                .ifEmpty { imgInLink?.attr("alt") ?: "" }
                .ifEmpty { container.selectFirst("h1, h2, h3, h4, .shortstoryHead a, .shortstoryHead")?.text() ?: "" }
                .ifEmpty { link.text() }
                .ifEmpty { "No title" }

            // Thumbnail: look for a poster-sized upload image in the whole card.
            // DLE puts posters under /uploads/; we prefer that over any other img.
            val posterImg = container.selectFirst("img[src*='/uploads/']")
                ?: container.selectFirst("img")
            posterImg?.let { img ->
                val src = img.attr("src").ifEmpty { img.attr("data-src") }
                if (src.isNotEmpty()) {
                    anime.thumbnail_url = when {
                        src.startsWith("http") -> src
                        src.startsWith("/") -> baseUrl.trimEnd('/') + src
                        else -> "${baseUrl.trimEnd('/')}/$src"
                    }
                }
            }

            animes.add(anime)
        }

        val hasNextPage = document.select(nextPageSelector).first() != null
        return AnimesPage(animes, hasNextPage)
    }

    // Video

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val document = response.asJsoup()
        val html = document.html()
        val fileData = Regex("\"file\"\\s*:\\s*\"(.+?)\"")
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { it.contains("http") }
            .maxByOrNull { it.length }
            ?: return emptyList()

        val qualityPattern = "\\[([^]]+)](.+?)(?=,\\[|\\$)".toRegex()

        qualityPattern.findAll(fileData).forEach { match ->
            val quality = match.groupValues[1]
            val urlsString = match.groupValues[2]

            val urls = urlsString
                .split(" or ")
                .map { it.trim() }
                .filter { it.startsWith("http") }

            urls.forEachIndexed { index, url ->
                val qualityLabel = if (urls.size > 1) {
                    "$quality - Mirror ${index + 1}"
                } else {
                    quality
                }

                videoList.add(Video(url, qualityLabel, url))
            }
        }

        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
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

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // Filters

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Не работают при текстовом поиске!"),
        AnimeFilter.Separator(),
        GenreFilter(getGenreList()),
        SortFilter(sortableList.map { it.first }.toTypedArray()),
    )

    private class GenreFilter(genres: Array<Pair<String, String>>) : UriPartFilter("Жанр", genres)

    private fun getGenreList() = arrayOf(
        Pair("Все", "all"),
        Pair("Боевые искусства", "boyevyye-iskusstva"),
        Pair("Война", "voyna"),
        Pair("Драма", "drama"),
        Pair("Детектив", "detektiv"),
        Pair("История", "istoriya"),
        Pair("Комедия", "komediya"),
        Pair("Мистика", "mistika"),
        Pair("Меха", "mekha"),
        Pair("Махо-сёдзё", "makho-sedze"),
        Pair("Музыкальный", "muzykalnyy"),
        Pair("Повседневность", "povsednevnost"),
        Pair("Приключения", "priklyucheniya"),
        Pair("Пародия", "parodiya"),
        Pair("Романтика", "romantika"),
        Pair("Сёнэн", "senen"),
        Pair("Сёдзё", "sedze"),
        Pair("Спорт", "sport"),
        Pair("Сказка", "skazka"),
        Pair("Сёдзё-ай", "sedze-ay"),
        Pair("Сёнэн-ай", "senen-ay"),
        Pair("Самураи", "samurai"),
        Pair("Триллер", "triller"),
        Pair("Ужасы", "uzhasy"),
        Pair("Фантастика", "fantastika"),
        Pair("Фэнтези", "fentezi"),
        Pair("Школа", "shkola"),
        Pair("Этти", "etti"),
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        override fun toString() = vals[state].second
    }

    private val sortableList = listOf(
        Pair("Дате", SortBy.DATE),
        Pair("Популярности", SortBy.RATING),
        Pair("Посещаемости", SortBy.NEWS_READ),
        Pair("Комментариям", SortBy.COMM_NUM),
        Pair("Алфавиту", SortBy.TITLE),
    )

    class SortFilter(sortables: Array<String>) : AnimeFilter.Sort("Сортировать по", sortables, Selection(0, false))

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("720p", "480p")
            entryValues = arrayOf("720", "480")
            setDefaultValue("480")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
