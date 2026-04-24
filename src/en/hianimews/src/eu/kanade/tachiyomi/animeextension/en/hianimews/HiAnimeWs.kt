package eu.kanade.tachiyomi.animeextension.en.hianimews

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.multisrc.animekaitheme.AnimeKaiTheme
import eu.kanade.tachiyomi.multisrc.animekaitheme.dto.VideoCode
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
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

    override fun popularAnimeFromElement(element: Element): SAnime = element.toSAnime()

    override fun popularAnimeNextPageSelector() = "nav > ul.pagination > li.active ~ li"

    // ============================== Related ==============================

    override fun relatedAnimeListSelector() = "li:has(a.dynamic-name), div.flw-item"

    override fun relatedAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val nameEl = element.selectFirst("a.dynamic-name")
        title = nameEl?.getTitle().orEmpty()
        setUrlWithoutDomain(nameEl?.attr("href").orEmpty())
        thumbnail_url = element.selectFirst("img.film-poster-img")?.attr("data-src").orEmpty()
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        val related = mutableListOf<SAnime>()
        val recommended = mutableListOf<SAnime>()

        doc.select("h2.cat-heading").forEach { header ->
            val headerText = header.text().trim()

            val targetList = when {
                headerText.contains("Related", ignoreCase = true) -> related
                headerText.contains("Recommended", ignoreCase = true) ||
                    headerText.contains("Popular", ignoreCase = true) -> recommended
                else -> null
            } ?: return@forEach

            val container = header.parents().firstOrNull { parent ->
                parent.selectFirst("li:has(a.dynamic-name), div.flw-item") != null
            }

            container?.select("li:has(a.dynamic-name), div.flw-item")?.forEach { el ->
                runCatching { targetList.add(relatedAnimeFromElement(el)) }
            }
        }

        return related + recommended
    }

    // =========================== Anime Details ============================

    override val coverSelector = "div.anis-cover"

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.anisc-poster img.film-poster-img")
            ?.attr("src").orEmpty()

        val fancyScore = when (scorePosition) {
            SCORE_POS_TOP, SCORE_POS_BOTTOM -> getFancyScore(
                document.selectFirst("div.rr-mark strong")?.ownText()?.trim(),
            )
            else -> ""
        }

        document.selectFirst("div.anisc-detail")?.let { info: Element ->
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

    private fun Element.toSAnime(): SAnime = SAnime.create().apply {
        selectFirst("a.film-poster-ahref")?.attr("href")?.let {
            setUrlWithoutDomain(it)
        }
        title = selectFirst("a.dynamic-name")?.getTitle().orEmpty()
        thumbnail_url = selectFirst("img.film-poster-img")?.attr("data-src").orEmpty()
    }
}
