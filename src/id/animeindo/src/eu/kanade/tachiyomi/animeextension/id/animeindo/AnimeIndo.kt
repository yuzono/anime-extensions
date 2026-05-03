package eu.kanade.tachiyomi.animeextension.id.animeindo

import android.util.Log
import aniyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.CountryFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.GenreFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.QualityFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.ReleaseFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.SortFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.TypeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.Executors

class AnimeIndo :
    AnimeStream(
        "id",
        "AnimeIndo",
        "https://animeindo.skin",
    ) {

    override val fetchFilters = false

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/trending?page=$page", headers)

    override fun popularAnimeSelector() = CARD_SELECTOR

    override fun popularAnimeFromElement(element: Element) = parseAnimeCard(element)

    override fun popularAnimeNextPageSelector() = NEXT_PAGE_SELECTOR

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/home", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val seen = mutableSetOf<String>()

        // Find ALL <a> tags on the page, then use Regex to check if it's an episode link
        document.select("a").forEach { element ->
            val href = element.attr("href")
            val match = Regex("""/episode/([a-z0-9-]+)/\d+-\d+""").find(href)

            if (match != null) {
                val slug = match.groupValues[1]

                // Deduplicate by slug so the same anime doesn't appear multiple times
                if (seen.add(slug)) {
                    animeList.add(
                        SAnime.create().apply {
                            setUrlWithoutDomain("/tv-show/$slug")
                            title = slug.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                            thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                        },
                    )
                }
            }
        }

        return AnimesPage(animeList, false)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/search/${encodePath(query)}?page=$page", headers)
        }

        val params = AnimeIndoFilters.getSearchParameters(filters)
        val urlBuilder = "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        params.sort.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("sort", it) }
        params.type.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("type", it) }
        params.quality.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("quality", it) }
        params.release.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("release", it) }

        params.genres.takeIf { it.isNotBlank() }?.let {
            val values = it.split("&").joinToString(",") { pair -> pair.substringAfter("=") }
            urlBuilder.addQueryParameter("genre", values)
        }

        params.countries.takeIf { it.isNotBlank() }?.let {
            val values = it.split("&").joinToString(",") { pair -> pair.substringAfter("=") }
            urlBuilder.addQueryParameter("country", values)
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeSelector() = CARD_SELECTOR

    override fun searchAnimeFromElement(element: Element) = parseAnimeCard(element)

    override fun searchAnimeNextPageSelector() = NEXT_PAGE_SELECTOR

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter("Sort"),
        TypeFilter("Type"),
        QualityFilter("Quality"),
        ReleaseFilter("Released"),
        AnimeFilter.Separator(),
        GenreFilter("Genre"),
        AnimeFilter.Separator(),
        CountryFilter("Country"),
    )

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        var doc = document
        var docUrl = document.location()

        // If we guessed /tv-show/ but it's actually a movie, the 404 page won't have a title.
        // We detect the blank title and fetch the correct /movie/ URL instead.
        if (docUrl.contains("/tv-show/")) {
            val initialTitle = doc.selectFirst("h1.text-3xl")?.text()
                ?: doc.selectFirst("h3.text-xl")?.text()

            if (initialTitle.isNullOrBlank()) {
                val slug = docUrl.removeSuffix("/").split("/").last()
                try {
                    client.newCall(GET("$baseUrl/movie/$slug", headers)).execute().use { res ->
                        if (res.isSuccessful) {
                            docUrl = "$baseUrl/movie/$slug"
                            doc = res.asJsoup()
                        }
                    }
                } catch (_: Exception) { /* Keep 404 state if movie endpoint also fails */ }
            }
        }

        return SAnime.create().apply {
            setUrlWithoutDomain(docUrl)

            title = doc.selectFirst("h1.text-3xl")?.text()
                ?: doc.selectFirst("h3.text-xl")?.text()
                ?: ""

            thumbnail_url = doc.select("img[src^=https]")
                .firstOrNull { it.attr("src").contains("/poster/") }
                ?.getImageUrl()
                ?: doc.selectFirst("meta[itemprop=image]")?.attr("content")

            genre = doc.select("div.my-6 a[href*=/genre/]").eachText().joinToString()
            status = SAnime.UNKNOWN

            description = buildString {
                doc.selectFirst("meta[property=og:description]")?.attr("content")?.let {
                    append(it)
                    append("\n\n")
                }

                doc.selectFirst("h2.text-lg")?.text()?.takeIf(String::isNotBlank)?.let {
                    append("Alternative name(s): $it\n")
                }

                doc.select("div.my-6.space-y-2 > div").forEach { row ->
                    val label = row.selectFirst("div:first-child")?.text()?.trim() ?: return@forEach

                    when (label) {
                        "Cast" -> {
                            val castMembers = row.selectFirst("div:last-child")?.select("a")?.eachText()
                            if (!castMembers.isNullOrEmpty()) {
                                append("$label: ${castMembers.joinToString(", ")}\n")
                            }
                        }
                        "Country" -> {
                            val country = row.selectFirst("div:last-child")?.selectFirst("a")?.text()?.trim()
                            if (!country.isNullOrBlank()) {
                                append("Country: $country\n")
                            }
                        }
                        "Genre" -> { /* Skip, handled above */ }
                        else -> {
                            val value = row.selectFirst("div:last-child")?.text()?.trim() ?: return@forEach
                            append("$label: $value\n")
                        }
                    }
                }
            }.replace(Regex("^(.+?)\\s+(.+?):\\s+\\2$", RegexOption.MULTILINE), "$1: $2")
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val requestUrl = response.request.url
        val pathSegments = requestUrl.pathSegments

        val slug = when {
            "tv-show" in pathSegments -> pathSegments.getOrNull(pathSegments.indexOf("tv-show") + 1).orEmpty()
            "movie" in pathSegments -> pathSegments.getOrNull(pathSegments.indexOf("movie") + 1).orEmpty()
            "episode" in pathSegments -> pathSegments.getOrNull(pathSegments.indexOf("episode") + 1).orEmpty()
            else -> pathSegments.lastOrNull().orEmpty()
        }

        if (slug.isBlank()) return emptyList()

        // Movies host their video servers directly on the details page, not on /episode/ pages.
        if ("movie" in pathSegments) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain("/movie/$slug")
                    name = "Movie"
                    episode_number = 1f
                },
            )
        }

        val episodes = mutableListOf<SEpisode>()
        val seenUrls = mutableSetOf<String>()
        var document = response.asJsoup()
        var currentSeasonNum = 0

        if ("episode" in pathSegments) {
            currentSeasonNum = pathSegments.lastOrNull()?.substringBefore("-")?.toIntOrNull() ?: 0
            parseEpisodes(document, slug, seenUrls, episodes)
        } else {
            parseEpisodes(document, slug, seenUrls, episodes)

            if (episodes.isNotEmpty()) {
                fixEpisodeNumbers(episodes)
                return episodes.sortedByDescending { it.episode_number }
            }
        }

        val executor = Executors.newFixedThreadPool(10)
        val probeFutures = (1..10).map { i ->
            executor.submit<Pair<Int, Document?>> {
                try {
                    client.newCall(GET("$baseUrl/episode/$slug/$i-1", headers)).execute().use { res ->
                        if (res.isSuccessful) Pair(i, res.asJsoup()) else Pair(i, null)
                    }
                } catch (_: Exception) {
                    Pair(i, null)
                }
            }
        }

        for (future in probeFutures) {
            val (season, doc) = future.get()
            if (doc != null) {
                document = doc
                currentSeasonNum = season
                parseEpisodes(document, slug, seenUrls, episodes)
                break
            }
        }
        executor.shutdown()

        if (currentSeasonNum == 0) return emptyList()

        val seasonNumbers = document.select("button").mapNotNull { btn ->
            Regex("Season (\\d+)").find(btn.text())?.groupValues?.get(1)?.toIntOrNull()
        }.distinct().sorted()

        val otherSeasons = seasonNumbers.filter { it != currentSeasonNum }
        if (otherSeasons.isNotEmpty()) {
            val executor = Executors.newFixedThreadPool(otherSeasons.size)
            val futures = otherSeasons.map { seasonNum ->
                executor.submit {
                    try {
                        client.newCall(GET("$baseUrl/episode/$slug/$seasonNum-1", headers)).execute().use { res ->
                            if (res.isSuccessful) {
                                synchronized(seenUrls) {
                                    parseEpisodes(res.asJsoup(), slug, seenUrls, episodes)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            futures.forEach { it.get() }
            executor.shutdown()
        }

        fixEpisodeNumbers(episodes)
        return episodes.sortedByDescending { it.episode_number }
    }

    private fun parseEpisodes(doc: Document, slug: String, seenUrls: MutableSet<String>, episodes: MutableList<SEpisode>) {
        doc.select(EPISODE_LINK_SELECTOR).forEach { element ->
            val epUrl = element.attr("abs:href").ifBlank { element.attr("href") }
            if (epUrl.contains("/episode/$slug/") && seenUrls.add(epUrl)) {
                episodes.add(episodeFromElement(element))
            }
        }
    }

    private fun fixEpisodeNumbers(episodes: MutableList<SEpisode>) {
        val grouped = episodes.groupBy { ep ->
            ep.url.substringAfterLast("/").substringBefore("-").toIntOrNull() ?: 1
        }

        val sortedSeasons = grouped.keys.sorted()

        var currentEpNumber = 1F
        for (season in sortedSeasons) {
            val seasonEps = grouped[season] ?: continue
            val sortedEps = seasonEps.sortedBy { ep ->
                ep.url.substringAfterLast("-").toFloatOrNull() ?: 0F
            }
            for (ep in sortedEps) {
                ep.episode_number = currentEpNumber++
            }
        }
    }

    override fun episodeListSelector() = EPISODE_LINK_SELECTOR

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))

        val childDivs = element.children().filter { it.tagName() == "div" }

        val urlTail = element.attr("href").substringAfterLast("/")
        val seasonNum = urlTail.substringBefore("-").toIntOrNull() ?: 1

        if (childDivs.size >= 2 && childDivs.any { it.selectFirst("h3") != null }) {
            val card = childDivs.first()
            val h3 = card.selectFirst("h3")
            val infoDiv = card.selectFirst("div.text-xs")
            val spans = infoDiv?.select("span")

            val epTitle = h3?.text() ?: ""
            val epNum = spans?.lastOrNull()?.text()?.replace("Episode ", "")?.trim()?.toFloatOrNull()
                ?: urlTail.substringAfter("-").toFloatOrNull()
                ?: 0F

            episode_number = epNum

            val epPart = "Episode ${epNum.toInt()}"
            name = when {
                epTitle.isNotBlank() && epTitle != "Episode ${epNum.toInt()}" -> "S$seasonNum $epPart - $epTitle"
                else -> "S$seasonNum $epPart"
            }
        } else if (childDivs.size >= 2) {
            val epText = childDivs[0].text()
            val epTitle = childDivs[1].text()

            val epNum = epText.replace("Episode #", "").trim().toFloatOrNull()
                ?: urlTail.substringAfter("-").toFloatOrNull()
                ?: 0F

            episode_number = epNum

            val epPart = "Episode ${epNum.toInt()}"
            name = when {
                epTitle.isNotBlank() && epTitle != "Episode ${epNum.toInt()}" -> "S$seasonNum $epPart - $epTitle"
                else -> "S$seasonNum $epPart"
            }
        } else {
            val card = element.parent()
            val h3 = card?.selectFirst("h3")
            val infoDiv = card?.selectFirst("div.text-xs")
            val spans = infoDiv?.select("span")

            val episodeText = spans?.lastOrNull()?.text() ?: ""
            val epTitle = h3?.text() ?: ""

            val epNum = episodeText.replace("Episode ", "").trim().toFloatOrNull()
                ?: urlTail.substringAfter("-").toFloatOrNull()
                ?: 0F

            episode_number = epNum

            val epPart = "Episode ${epNum.toInt()}"
            val hasTitle = epTitle.isNotBlank() && epTitle != episodeText && epTitle != epPart

            name = when {
                hasTitle -> "S$seasonNum $epPart - $epTitle"
                else -> "S$seasonNum $epPart"
            }
        }
    }

    // ============================ Video Links =============================
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val gdrivePlayerExtractor by lazy { GdrivePlayerExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    override fun getVideoList(url: String, name: String): List<Video> = with(name) {
        when {
            contains("streamtape") -> streamTapeExtractor.videoFromUrl(url)?.let(::listOf).orEmpty()

            contains("mp4") -> mp4uploadExtractor.videosFromUrl(url, headers)

            contains("yourupload") -> yourUploadExtractor.videoFromUrl(url, headers)

            url.contains("ok.ru") -> okruExtractor.videosFromUrl(url)

            contains("gdrive") -> {
                val gdriveUrl = when {
                    baseUrl in url -> "https:" + url.toHttpUrl().queryParameter("data")!!
                    else -> url
                }
                gdrivePlayerExtractor.videosFromUrl(gdriveUrl, "Gdrive", headers)
            }

            else -> {
                Log.i("AnimeIndo", "Unrecognized at getVideoList => Name -> $name || URL => $url")
                emptyList()
            }
        }
    }

    // ============================== Helpers ===============================
    companion object {
        private const val CARD_SELECTOR = "div.relative.group.overflow-hidden"
        private const val NEXT_PAGE_SELECTOR = "button[dusk=nextPage]"
        private const val EPISODE_LINK_SELECTOR = "a[href*=/episode/]"
    }

    private fun parseAnimeCard(element: Element) = SAnime.create().apply {
        val a = element.selectFirst("a")!!
        setUrlWithoutDomain(a.attr("href"))
        title = element.selectFirst("h3")!!.text()
        thumbnail_url = element.selectFirst("img")?.getImageUrl()
    }

    private fun encodePath(segment: String): String = segment
        .replace("%", "%25")
        .replace("/", "%2F")
        .replace(" ", "-")
}
