package eu.kanade.tachiyomi.animeextension.en.hianimews

import android.util.Log
import eu.kanade.aniyomi.lib.megaupextractor.MegaUpExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animekaitheme.AnimeKaiTheme
import eu.kanade.tachiyomi.multisrc.animekaitheme.dto.ResultResponse
import eu.kanade.tachiyomi.multisrc.animekaitheme.dto.VideoCode
import eu.kanade.tachiyomi.multisrc.animekaitheme.dto.VideoData
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HiAnimeWs :
    AnimeKaiTheme(
        "en",
        "HiAnimeWs",
        domainEntries = listOf("hianime.ws"),
        hosterNames = listOf("Server 1", "Server 2"),
    ) {

    // ============================ Headers & Client =========================

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Extractor ==============================

    private var megaUpExtractor by LazyMutable { MegaUpExtractor(client, docHeaders) }

    override fun updateDomainConfig() {
        super.updateDomainConfig()
        megaUpExtractor = MegaUpExtractor(client, docHeaders)
    }

    override suspend fun extractVideo(server: VideoData): List<Video> = try {
        megaUpExtractor.videosFromUrl(server.iframe, server.serverName)
    } catch (e: Exception) {
        Log.e(name, "Error extracting videos for ${server.serverName}", e)
        emptyList()
    }

    // ============================== Popular ===============================

    override fun popularAnimeSelector() = "div.flw-item"

    override fun popularAnimeFromElement(element: Element): SAnime = element.toSAnime()

    override fun popularAnimeNextPageSelector() = "nav > ul.pagination > li.active ~ li"

    // ============================== Related ==============================

    override fun relatedAnimeListSelector() = "div.flw-item"

    override fun relatedAnimeFromElement(element: Element): SAnime = element.toSAnime()

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        val seasons = document.select("#seasons div.season div.aitem div.inner")
            .mapNotNull { it.toSeasonSAnime() }
        val related = document.select(relatedAnimeListSelector()).mapNotNull {
            runCatching { relatedAnimeFromElement(it) }.getOrNull()
        }
        return seasons + related
    }

    // =========================== Anime Details ============================

    private val coverSelector by lazy { "div.anis-cover" }

    private fun Document.getCover(): String? = selectFirst(coverSelector)?.getBackgroundImage()

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.anisc-poster img.film-poster-img")
            ?.attr("src").orEmpty()

        val fancyScore = when (scorePosition) {
            SCORE_POS_TOP, SCORE_POS_BOTTOM -> getFancyScore(
                document.selectFirst("div.rr-mark strong")?.ownText()?.trim(),
            )
            else -> ""
        }

        document.selectFirst("div.anisc-detail")?.let { info ->
            title = info.selectFirst("h2.film-name a.dynamic-name")?.getTitle().orEmpty()

            val producers = info.select("div.film-text a[href*=/producers/]").eachText()
                .joinToString()
            author = producers.ifBlank { null }

            description = buildString {
                if (scorePosition == SCORE_POS_TOP && fancyScore.isNotEmpty()) {
                    append(fancyScore)
                    append("\n\n")
                }

                info.selectFirst("div.film-description div.text")?.text()?.let {
                    append(it + "\n")
                }

                if (producers.isNotBlank()) {
                    append("\n**Producers:** $producers")
                }

                document.getCover()?.let { append("\n\n![Cover]($it)") }

                if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(fancyScore)
                }
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = "div.eplist a"

    override fun episodeFromElement(element: Element): SEpisode {
        val token = element.attr("token").ifEmpty {
            throw IllegalStateException("Token not found")
        }
        val epNum = element.attr("num")
        val subdubType = element.attr("langs").toIntOrNull() ?: 0
        val subdub = when (subdubType) {
            1 -> "Sub"
            3 -> "Dub & Sub"
            else -> ""
        }

        val namePrefix = "Episode $epNum"
        val episodeTitle = element.selectFirst("span")?.text()
            ?.takeIf { it.isNotBlank() && it != namePrefix }
            ?.let { ": $it" }
            .orEmpty()

        return SEpisode.create().apply {
            name = namePrefix + episodeTitle
            url = token
            episode_number = epNum.toFloat()
            scanlator = subdub
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = client.newCall(animeDetailsRequest(anime))
            .awaitSuccess().use {
                val document = it.asJsoup()
                document.selectFirst("div[data-id]")?.attr("data-id")
                    ?: throw IllegalStateException("Anime ID not found")
            }

        val enc = encDecEndpoints(animeId)

        // Extracted out of .use { } to prevent reified type inference issues with parseAs<T>
        val episodeDocument = client.newCall(
            GET("$baseUrl/ajax/episodes/list?ani_id=$animeId&_=$enc", docHeaders),
        ).awaitSuccess().parseAs<ResultResponse>().toDocument()

        return episodeDocument.select(episodeListSelector()).mapNotNull {
            runCatching { episodeFromElement(it) }.getOrNull()
        }.reversed()
    }

    // ============================ Video List ==============================

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val token = episode.url
        val enc = encDecEndpoints(token)

        // Extracted out of .use { } to prevent reified type inference issues with parseAs<T>
        val document = client.newCall(
            GET("$baseUrl/ajax/links/list?token=$token&_=$enc", docHeaders),
        ).awaitSuccess().parseAs<ResultResponse>().toDocument()

        val servers = parseServersFromHtml(document)

        return servers.parallelCatchingMapNotNull { server ->
            try {
                extractIframe(server)
            } catch (e: Exception) {
                null
            }
        }.parallelCatchingFlatMap { server ->
            try {
                extractVideo(server)
            } catch (e: Exception) {
                emptyList<Video>() // Explicit type to prevent T inference error
            }
        }
    }

    // ============================= Utilities ==============================

    private fun parseServersFromHtml(document: Document): List<VideoCode> {
        return document.select("div.ps_-block").flatMap { typeBlock ->
            val typeClass = typeBlock.classNames().find { it.startsWith("ps_-block-") }
            val type = typeClass?.removePrefix("ps_-block-") ?: return@flatMap emptyList<VideoCode>() // Explicit type

            if (type !in typeToggle) return@flatMap emptyList<VideoCode>() // Explicit type

            typeBlock.select("a.server[data-lid]").mapNotNull { serverElm ->
                val serverId = serverElm.attr("data-lid")
                val serverName = serverElm.text()
                if (serverName !in hostToggle) return@mapNotNull null

                VideoCode(type, serverId, serverName)
            }
        }
    }

    private suspend fun encDecEndpoints(enc: String): String {
        val url = "https://enc-dec.app/api/enc-kai".toHttpUrl().newBuilder()
            .addQueryParameter("text", enc)
            .build()

        return client.newCall(GET(url, encDecHeaders()))
            .awaitSuccess().parseAs<ResultResponse>().result
    }

    private suspend fun extractIframe(server: VideoCode): VideoData {
        val (type, serverId, serverName) = server

        val enc = encDecEndpoints(serverId)

        val encodedLink = client.newCall(
            GET("$baseUrl/ajax/links/view?id=$serverId&_=$enc", docHeaders),
        ).awaitSuccess().parseAs<ResultResponse>().result

        val iframe = decryptIframeData(encodedLink, encDecHeaders())
        val typeSuffix = getTypeSuffix(type)
        val name = "$serverName | [$typeSuffix]"

        return VideoData(iframe, name)
    }

    private fun encDecHeaders(): Headers = headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Content-Type", "application/json")
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/watch")
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "cross-site")
        .removeAll("Sec-Fetch-User")
        .removeAll("Upgrade-Insecure-Requests")
        .build()

    /**
     * Builds an SAnime from a standard flw-item element.
     */
    private fun Element.toSAnime(): SAnime = SAnime.create().apply {
        selectFirst("a.film-poster-ahref")?.attr("href")?.let {
            setUrlWithoutDomain(it)
        }
        title = selectFirst("a.dynamic-name")?.getTitle().orEmpty()
        thumbnail_url = selectFirst("img.film-poster-img")?.attr("data-src").orEmpty()
    }

    /**
     * Builds an SAnime from a season item element.
     */
    private fun Element.toSeasonSAnime(): SAnime? = SAnime.create().apply {
        val url = selectFirst("a")?.attr("href") ?: return null
        setUrlWithoutDomain(url)
        thumbnail_url = selectFirst("img")?.attr("src")
        title = select("div.detail span").text()
    }

    private fun Element.getTitle(): String {
        val enTitle = attr("title")
        val jpTitle = attr("data-jp")
        return if (useEnglish) {
            enTitle.ifBlank { text() }
        } else {
            jpTitle.ifBlank { text() }
        }
    }
}
