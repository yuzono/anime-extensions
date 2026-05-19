package eu.kanade.tachiyomi.animeextension.en.animekai

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.multisrc.animekaitheme.AnimeKaiTheme
import eu.kanade.tachiyomi.multisrc.animekaitheme.dto.VideoCode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeKai :
    AnimeKaiTheme(
        "en",
        "AnimeKai",
        // Domain list: https://animekai.pw
        domainEntries = listOf(
            "animekai.to",
            "animekai.fi",
            "animekai.fo",
            "animekai.gs",
            "animekai.la",
            "anikai.to",
        ),
        hosterNames = listOf("Server 1", "Server 2"),
    ) {

    // ============================== Popular ===============================

    override fun popularAnimeSelector() = "div.aitem-wrapper div.aitem"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.poster")?.attr("href")!!)
        title = element.selectFirst("a.title")?.getTitle()!!
        thumbnail_url = element.select("a.poster img").attr("data-src")
    }

    override fun popularAnimeNextPageSelector() = "nav > ul.pagination > li.active ~ li"

    // =========================== Anime Details ============================

    override val backgroundSelector = "div.watch-section-bg"

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.select(".poster img").attr("src")

        // fancy score
        val fancyScore = when (scorePosition) {
            SCORE_POS_TOP, SCORE_POS_BOTTOM -> getFancyScore(
                document.selectFirst("#anime-rating")?.attr("data-score"),
            )
            else -> ""
        }

        document.selectFirst("div#main-entity")?.let { info: Element ->
            val titleElement = info.selectFirst("h1.title")
            titleElement?.getTitle()?.let { title = it }

            val titles = titleElement?.let { elm: Element ->
                listOf(
                    elm.attr("title"),
                    elm.attr("data-jp"),
                    elm.ownText(),
                )
            } ?: emptyList()

            val altTitles = (
                info.selectFirst(".al-title")?.text()?.split(";").orEmpty() +
                    titles
                )
                .asSequence().map { it.trim() }
                .filterNot { it.isBlank() || it.equals(title, ignoreCase = true) }
                .distinctBy { it.lowercase() }.joinToString("; ")

            val rating = info.selectFirst(".rating")?.text().orEmpty()

            info.selectFirst("div.detail")?.let { detail: Element ->
                author = detail.getInfo("Studios:", isList = true)?.takeIf { it.isNotEmpty() }
                    ?: detail.getInfo("Producers:", isList = true)?.takeIf { it.isNotEmpty() }
                status = detail.getInfo("Status:")?.run(::parseStatus) ?: SAnime.UNKNOWN
                genre = detail.getInfo("Genres:", isList = true)

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
                    detail.getInfo("MAL:", full = true)?.run(::append)
                    if (altTitles.isNotBlank()) {
                        append("\n**Alternative Title:** $altTitles")
                    }
                    detail.select("div:containsOwn(Links:) a")
                        .forEach { append("\n[${it.text()}](${it.attr("href")})") }
                    document.getBackground()?.let { append("\n\n![Cover]($it)") }

                    if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append(fancyScore)
                    }
                }
            }
        } ?: throw IllegalStateException("Invalid anime details page format")
    }

    // ============================= Utilities ==============================

    override fun parseServersFromHtml(document: Document): List<VideoCode> {
        return document.select("div.server-items[data-id]").flatMap { typeElm ->
            val type = typeElm.attr("data-id") // sub, softsub, dub
            if (type !in typeToggle) return@flatMap emptyList()

            typeElm.select("span.server[data-lid]")
                .mapNotNull { serverElm ->
                    val serverId = serverElm.attr("data-lid")
                    val serverName = serverElm.text()
                    if (serverName !in hostToggle) return@mapNotNull null

                    VideoCode(type, serverId, serverName)
                }
        }
    }
}
