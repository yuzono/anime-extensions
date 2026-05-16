package eu.kanade.tachiyomi.animeextension.en.hianimews

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.multisrc.animekaitheme.AnimeKaiTheme
import eu.kanade.tachiyomi.multisrc.animekaitheme.dto.VideoCode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HiAnimeWs :
    AnimeKaiTheme(
        "en",
        "HiAnimeWs (Unoriginal)",
        domainEntries = listOf("hianime.ws"),
        hosterNames = listOf("Server 1", "Server 2"),
    ) {

    // ============================== Popular ===============================

    override fun popularAnimeSelector() = "div.flw-item"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.film-poster-ahref")?.attr("href")!!)
        title = element.selectFirst("a.dynamic-name")?.getTitle()!!
        thumbnail_url = element.selectFirst("img.film-poster-img")?.attr("data-src")
    }

    override fun popularAnimeNextPageSelector() = "nav > ul.pagination > li.active ~ li"

    // ============================== Related ==============================

    override fun relatedAnimeListSelector() = "section:has(.cat-heading:contains(Related Anime)) li"
    override fun recommendedAnimeListSelector() = "section:has(.cat-heading:contains(Recommended for you)) ${popularAnimeSelector()}"

    override fun relatedAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        element.selectFirst(".film-name a")!!.let { it: Element ->
            setUrlWithoutDomain(it.attr("abs:href"))
            title = it.getTitle()!!
        }
        thumbnail_url = element.selectFirst("img.film-poster-img")?.attr("data-src").orEmpty()
    }

    // =========================== Anime Details ============================

    override val backgroundSelector = "div.anis-cover"

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.anisc-poster img.film-poster-img")
            ?.attr("src")

        val fancyScore = when (scorePosition) {
            SCORE_POS_TOP, SCORE_POS_BOTTOM -> getFancyScore(
                document.selectFirst("div.rr-mark strong")?.ownText(),
            )
            else -> ""
        }

        document.selectFirst("div.anisc-detail")?.let { info: Element ->
            info.selectFirst("h2.film-name a.dynamic-name")?.getTitle()?.let { title = it }

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

                document.getBackground()?.let { append("\n\n![Cover]($it)") }

                if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(fancyScore)
                }
            }
        }
    }

    // ============================= Utilities ==============================

    override fun parseServersFromHtml(document: Document): List<VideoCode> {
        return document.select("div.ps_-block").flatMap { typeBlock ->
            val typeClass = typeBlock.classNames().find { it.startsWith("ps_-block-") }
            val type = typeClass?.removePrefix("ps_-block-") ?: return@flatMap emptyList<VideoCode>()

            if (type !in typeToggle) return@flatMap emptyList()

            typeBlock.select("a.server[data-lid]").mapNotNull { serverElm ->
                val serverId = serverElm.attr("data-lid")
                val serverName = serverElm.text()
                if (serverName !in hostToggle) return@mapNotNull null

                VideoCode(type, serverId, serverName)
            }
        }
    }
}
