package eu.kanade.tachiyomi.animeextension.en.nineanimetv

import aniyomi.lib.rapidcloudextractor.RapidCloudExtractor
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class NineAnimeTv :
    ZoroTheme(
        "en",
        "9AnimeTV",
        "https://9animetv.to",
        hosterNames = listOf(
            "DouVideo",
            "Vidstreaming",
            "Vidcloud",
        ),
    ) {
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recently-updated?page=$page", docHeaders)

    override fun popularAnimeNextPageSelector() = ".anime-pagination div.ap__-btn-next a:not(.disabled)"

    override fun popularAnimeRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/home", docHeaders)
    } else {
        super.popularAnimeRequest(page - 1)
    }

    private val topViewSelector = "#top-viewed-month li, #top-viewed-week li, #top-viewed-day li"

    override suspend fun getPopularAnime(page: Int): AnimesPage = client.newCall(popularAnimeRequest(page))
        .awaitSuccess()
        .use { response ->
            if (page == 1) {
                val document = response.asJsoup()

                val animes = document.select(topViewSelector).map { element ->
                    popularAnimeFromElement(element)
                }

                AnimesPage(animes, true)
            } else {
                popularAnimeParse(response)
            }
        }

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.anime-poster img")!!.attr("src")

        document.selectFirst("div.film-infor")!!.let { info ->
            author = info.getInfo("Studios")
            status = parseStatus(info.getInfo("Status"))
            genre = info.getInfo("Genre", isList = true)

            description = buildString {
                info.select(".film-description").text().also { append(it + "\n") }
                info.select(".alias").text().also { append("\nAlternative: $it") }
                info.getInfo("Date aired:", full = true)?.also(::append)
                info.getInfo("Premiered:", full = true)?.also(::append)
                info.getInfo("Synonyms:", full = true)?.also(::append)
                info.getInfo("Japanese:", full = true)?.also(::append)
                info.getInfo("Scores:", full = true)?.also(::append)
                info.getInfo("Type:", full = true)?.also(::append)
                info.getInfo("Duration:", full = true)?.also(::append)
                info.getInfo("Quality:", full = true)?.also(::append)
                info.getInfo("Views:", full = true)?.also(::append)
            }
        }
    }

    override fun Element.getInfo(
        tag: String,
        isList: Boolean,
        full: Boolean,
    ): String? {
        if (isList) {
            return select("div.item-title:contains($tag) + .item-content > a").eachText().joinToString()
        }
        val value = selectFirst("div.item-title:contains($tag) + .item-content")
            ?.selectFirst("*.name, *.text, *.item-content")
            ?.text()
        return if (full && value != null) "\n$tag $value" else value
    }

    private val rapidCloudExtractor by lazy { RapidCloudExtractor(client, headers, preferences) }

    override fun extractVideo(server: VideoData): List<Video> = when (server.name) {
        "DouVideo", "Vidstreaming", "Vidcloud" -> rapidCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)
        else -> emptyList()
    }
}
