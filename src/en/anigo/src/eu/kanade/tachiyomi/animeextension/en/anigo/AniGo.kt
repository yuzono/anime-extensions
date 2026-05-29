package eu.kanade.tachiyomi.animeextension.en.anigo

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.animekaitheme.AnimeKaiTheme
import eu.kanade.tachiyomi.multisrc.animekaitheme.dto.AniGoEpTokenResponse
import eu.kanade.tachiyomi.multisrc.animekaitheme.dto.AniGoEpisodesResponse
import eu.kanade.tachiyomi.multisrc.animekaitheme.dto.AniGoLinkResponse
import eu.kanade.tachiyomi.multisrc.animekaitheme.dto.VideoCode
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AniGo :
    AnimeKaiTheme(
        "en",
        "AniGo",
        domainEntries = listOf("anigo.to"),
        hosterNames = listOf("Server 1", "Server 2"),
    ) {

    // ============================ Headers & Client =========================

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Upgrade-Insecure-Requests", "1")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("Sec-Fetch-User", "?1")

    private fun apiHeaders(referer: String = "$baseUrl/"): Headers = headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("X-Requested-With", "XMLHttpRequest")
        .set("Referer", referer)
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")
        .removeAll("Sec-Fetch-User")
        .removeAll("Upgrade-Insecure-Requests")
        .build()

    // ============================== Popular ===============================

    override fun popularAnimeSelector() = "div.aniCard.medium div.unit:has(a[href^=/watch/])"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val href = element.selectFirst("a.poster")?.attr("abs:href")
            ?.takeIf { it.startsWith("$baseUrl/watch/") }!!

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            title = element.selectFirst(".title")?.getTitle()!!
            thumbnail_url = element.selectFirst("a.poster img")?.attr("abs:src")
        }
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination a[rel=next]"

    // ============================== Related ==============================

    override fun relatedAnimeListSelector() = "section:has(.sectionTitle:contains(RELATED)) .aniCard a.unit"
    override fun recommendedAnimeListSelector() = "section:has(.sectionTitle:contains(RECOMMENDED)) .aniCard a.unit"

    override fun relatedAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href").takeIf(String::isNotBlank)!!)
        title = element.selectFirst(".title")?.getTitle()!!
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    // =========================== Anime Details ============================

    private val jTitleRegex by lazy { """JTitle\(`([^`]*)`\)""".toRegex() }
    override val backgroundSelector = "div.playerBG"

    override fun Element.getTitle(): String? {
        val enTitle = text()
        val xData = attr("x-data")
        val romajiTitle = jTitleRegex.find(xData)?.groupValues?.getOrNull(1)?.trim()
        return if (useEnglish) {
            enTitle.takeUnless(String::isNullOrBlank)
                ?: romajiTitle?.takeIf(String::isNotBlank)
        } else {
            romajiTitle?.takeIf(String::isNotBlank)
                ?: enTitle?.takeIf(String::isNotBlank)
        }
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.select(".poster img").attr("abs:src")

        val fancyScore = when (scorePosition) {
            SCORE_POS_TOP, SCORE_POS_BOTTOM -> getFancyScore(document.selectFirst("div.rate-box span")?.text())
            else -> ""
        }

        document.selectFirst("div#main-entity")?.let { info: Element ->
            val titleEl = info.selectFirst("div.title")
            titleEl?.getTitle()?.let { title = it }

            val altTitles = info.selectFirst("small.subTitle")?.text()?.split(";").orEmpty()
                .asSequence().map { it.trim() }
                .filterNot { it.isBlank() || it.equals(title, ignoreCase = true) }
                .distinctBy { it.lowercase() }.joinToString("; ")

            val rating = info.selectFirst(".rating")?.text().orEmpty()

            info.selectFirst("div.detail")?.let { detail: Element ->
                author = detail.getInfo("Studios:", isList = true)?.takeIf { it.isNotEmpty() }
                    ?: detail.getInfo("Producers:", isList = true)?.takeIf { it.isNotEmpty() }
                status = detail.getInfo("Status:")?.run(::parseStatus) ?: SAnime.UNKNOWN

                description = buildString {
                    if (scorePosition == SCORE_POS_TOP && fancyScore.isNotEmpty()) {
                        append(fancyScore)
                        append("\n\n")
                    }
                    info.selectFirst(".desc")?.text()?.let { append(it + "\n") }
                    detail.getInfo("Country:", full = true)?.run(::append)
                    detail.getInfo("Premiered:", full = true)?.run(::append)
                    detail.getInfo("Date aired:", full = true)?.run(::append)
                    detail.getInfo("Broadcast:", full = true)?.run(::append)
                    detail.getInfo("Duration:", full = true)?.run(::append)
                    if (rating.isNotBlank()) append("\n**Rating:** $rating")
                    detail.select("div:containsOwn(Links:) a")
                        .forEach { append("\n[${it.text()}](${it.attr("abs:href")})") }
                    if (altTitles.isNotBlank()) {
                        append("\n**Alternative Title:** $altTitles")
                    }
                    document.getBackground()?.let { append("\n\n![Cover]($it)") }
                    if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append(fancyScore)
                    }
                }
            }
            genre = info.select("div.genre a").eachText().joinToString()
        } ?: throw IllegalStateException("Invalid anime details page format")
    }

    // ============================== Episodes ==============================

    private val idRegex by lazy { Regex("""id:\s*'([^']+)'""") }

    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override suspend fun fetchEpisodeAnimeId(anime: SAnime): String = client.newCall(animeDetailsRequest(anime))
        .awaitSuccess()
        .useAsJsoup()
        .selectFirst("div.playZone")?.attr("x-data")
        ?.let { xData ->
            idRegex.find(xData)?.groupValues?.getOrNull(1)
                ?: throw IllegalStateException("Anime ID not found in x-data")
        }
        ?: throw IllegalStateException("Anime ID not found (no playZone element)")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = "$baseUrl${anime.url}"
        val animeId = fetchEpisodeAnimeId(anime)
        val enc = encDecEndpoints(animeId)

        val episodesResponse = client.newCall(
            GET("$baseUrl/api/v1/titles/$animeId/episodes?_=$enc", apiHeaders(animeUrl)),
        ).awaitSuccess().parseAs<AniGoEpisodesResponse>()

        if (episodesResponse.status != "ok") {
            throw IllegalStateException("Failed to fetch episodes: ${episodesResponse.status}")
        }

        return episodesResponse.result.rangedEpisodes.flatMap { it.episodes }.map { ep ->
            val subdub = when (ep.langs) {
                1 -> "Sub"
                2 -> "Dub"
                3 -> "Dub & Sub"
                else -> ""
            }
            val namePrefix = "Episode ${ep.number}"
            val detailName = ep.detailName?.takeIf { it.isNotBlank() && it != namePrefix }?.let { ": $it" }.orEmpty()
            val fillerTag = if (ep.isFiller == 1) " (Filler)" else ""

            SEpisode.create().apply {
                name = namePrefix + detailName + fillerTag
                url = ep.token
                episode_number = ep.number.toFloat()
                scanlator = subdub
            }
        }.reversed()
    }

    // ============================ Video List ==============================

    override suspend fun fetchServers(token: String, enc: String): List<VideoCode> {
        val typeSelection = typeToggle
        val hosterSelection = hostToggle

        val epTokenResponse = client.newCall(
            GET("$baseUrl/api/v1/eptokens/$token?_=$enc", apiHeaders("$baseUrl/watch")),
        ).awaitSuccess().parseAs<AniGoEpTokenResponse>()

        if (epTokenResponse.status != "ok") {
            throw IllegalStateException("AniGo: failed to load video list, ep token status=${epTokenResponse.status}")
        }

        return epTokenResponse.result.flatMap { epToken ->
            if (epToken.lang !in typeSelection) return@flatMap emptyList()
            epToken.links.mapNotNull { link ->
                if (link.serverTitle !in hosterSelection) return@mapNotNull null
                VideoCode(epToken.lang, link.id, link.serverTitle)
            }
        }
    }

    override fun parseServersFromHtml(document: Document) = throw UnsupportedOperationException()

    override suspend fun fetchEncodedLink(lid: String, enc: String): String {
        val linkResponse = client.newCall(
            GET("$baseUrl/api/v1/links/$lid?_=$enc", apiHeaders("$baseUrl/watch")),
        ).awaitSuccess().parseAs<AniGoLinkResponse>()

        if (linkResponse.status != "ok") throw IllegalStateException("Failed to fetch link: ${linkResponse.status}")

        return linkResponse.result
    }
}
