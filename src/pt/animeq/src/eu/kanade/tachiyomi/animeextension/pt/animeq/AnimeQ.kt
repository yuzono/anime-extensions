package eu.kanade.tachiyomi.animeextension.pt.animeq

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animeextension.pt.animeq.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeQ :
    DooPlay(
        "pt-BR",
        "AnimeQ",
        "https://animeq.net",
    ) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "article.w_item_a > a, article.w_item_b > a"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime", headers)

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div.pagination > a.arrow_pag > i.fa-caret-right"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val params = AnimeQFilters.getSearchParameters(filterList)

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            filterList.firstOrNull { it is UriPartFilter && it.state != 0 }?.let {
                addEncodedPathSegments((it as UriPartFilter).toUriPart())
            }

            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }

            addPathSegment("")
            addQueryParameter("s", query)

            params.orderBy?.let { addQueryParameter("orderby", it) }
            params.order?.let { addQueryParameter("order", it) }
        }.build()

        return GET(url.toString(), headers)
    }

    // =========================== Anime Details ============================
    override val additionalInfoSelector = "div.wp-content"

    override fun Document.getDescription(): String = select("$additionalInfoSelector p")
        .firstOrNull { !it.text().contains("Título Alternativo") }
        ?.let { it.text().substringAfter("Sinopse: ") + "\n" }
        ?: ""

    fun Document.getAlternativeTitle(): String = select("$additionalInfoSelector p")
        .firstOrNull { it.text().contains("Título Alternativo") }
        ?.let { it.text() + "\n" }
        ?: ""

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")!!
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            val posterImg = requireNotNull(sheader.selectFirst("div.poster > img"))
            thumbnail_url = posterImg.getImageUrl()
            title = posterImg.attr("alt").ifEmpty {
                sheader.selectFirst("div.data > h1")?.text() ?: ""
            }.trim()

            genre = sheader.select("div.data div.sgeneros > a")
                .eachText()
                .joinToString()

            val info = doc.selectFirst("div#info") ?: return@apply
            description = buildString {
                append(doc.getDescription())
                appendLine()
                append(doc.getAlternativeTitle())
                additionalInfoItems.forEach { item ->
                    info.getInfo(item)?.let { value -> append(value) }
                }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.episodios-grid > div.episode-card"

    override fun episodeFromElement(element: Element, seasonName: String): SEpisode = SEpisode.create().apply {
        val epNum = element.attr("data-episode-number").trim()
        val href = element.selectFirst("a[href]")!!
        val episodeName = element.attr("data-episode-title").trim()
        episode_number = epNum.toFloatOrNull() ?: 0F
        name = "$episodeSeasonPrefix $seasonName x $epNum - $episodeName"
        setUrlWithoutDomain(href.attr("href"))
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private suspend fun getPlayerVideos(player: Element): List<Video> {
        val name = player.selectFirst("span.title")?.text()
            ?.run {
                when (this.uppercase()) {
                    "SD" -> "360p"
                    "HD" -> "720p"
                    "SD/HD", "SD / HD" -> "720p"
                    "FHD", "FULLHD", "FULLHD / HLS" -> "1080p"
                    else -> this
                }
            } ?: "Player"

        val url = getPlayerUrl(player)
        if (url.isEmpty() || !url.startsWith("http")) return emptyList()

        val videos = when {
            "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)
            "jwplayer?source=" in url -> {
                val videoUrl = url.toHttpUrl().queryParameter("source") ?: return emptyList()

                val videoHeaders = headers.newBuilder()
                    .add("Accept", "*/*")
                    .add("Host", videoUrl.toHttpUrl().host)
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()

                return listOf(
                    Video(videoUrl, name, videoUrl, videoHeaders),
                )
            }

            else -> emptyList()
        }

        if (videos.isEmpty()) {
            return universalExtractor.videosFromUrl(url, headers, name)
        }
        return videos
    }

    private suspend fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        return client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num"))
            .awaitSuccess().bodyString()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
    }

    // ============================== Filters ===============================
    @Volatile
    private var hasFetchedGenresArray = false

    override val genreFilterHeader = "Apenas um tipo de filtro por vez"
    override fun genresListRequest() = GET("$baseUrl/wp-json/wp/v2/genres?per_page=100&_fields[]=name&_fields[]=link")

    override fun getFilterList(): AnimeFilterList = if (hasFetchedGenresArray) {
        AnimeFilterList(
            AnimeFilter.Header(genreFilterHeader),
            AnimeQFilters.AudioFilter(),
            FetchedGenresFilter(genresListMessage, genresArray),
            AnimeFilter.Separator(),
            AnimeQFilters.OrderByFilter(),
            AnimeQFilters.OrderFilter(),
        )
    } else if (fetchGenres) {
        AnimeFilterList(AnimeFilter.Header(genresMissingWarning))
    } else {
        AnimeFilterList()
    }

    @Synchronized
    override fun fetchGenresList() {
        if (hasFetchedGenresArray || !fetchGenres) return

        runCatching {
            client.newCall(genresListRequest())
                .execute()
                .parseAs<List<GenreDto>>()
                .let(::genresListParse)
                .let { items ->
                    if (items.isNotEmpty()) {
                        genresArray = items
                        hasFetchedGenresArray = true
                    }
                }
        }.onFailure { it.printStackTrace() }
    }

    fun genresListParse(genres: List<GenreDto>): Array<Pair<String, String>> {
        val items = genres.map {
            val name = it.name
            val value = it.link.substringAfter("$baseUrl/").removeSuffix("/")
            Pair(name, value)
        }.toTypedArray()

        return if (items.isEmpty()) {
            items
        } else {
            arrayOf(Pair(selectFilterText, "")) + items
        }
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending {
                    REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    override fun Element.getImageUrl(): String {
        val url = when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }

        // Remove the "-<width>x<height>" suffix before the file extension:
        // ex: ".../file-200x300.jpg" -> ".../file.jpg"
        return url.replace(REGEX_IMAGE_SIZE_SUFFIX, "")
    }

    @Serializable
    data class GenreDto(
        val name: String,
        val link: String,
    )

    companion object {
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
        private val REGEX_IMAGE_SIZE_SUFFIX by lazy {
            Regex("""-\d+x\d+(?=\.[A-Za-z0-9]+$)""")
        }
    }
}
