package eu.kanade.tachiyomi.animeextension.en.anichi

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme
import eu.kanade.tachiyomi.multisrc.anikototheme.dto.ResultResponse
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Anichi :
    AnikotoTheme(
        "en",
        "Anichi",
        // https://megaplay.buzz/domains
        domainEntries = listOf(
            "anichi.to",
        ),
        hosterNames = listOf("HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream", "VidPlay-1"), // seed/fallback only
    ) {
    override val hasSourceFilter = true
    override val hasEpisodeFilter = true

    // ======================= Selector Overrides ===========================

    override fun popularAnimeSelector(): String = "div.ani.items > div.item"
    override val listingThumbnailSelector = "div.ani.poster img"
    override val synopsisContentSelector = ".series-blurb__full, .series-blurb__short"
    override val detailThumbnailSelector = ".series-intro__poster img"

    override val watchOrderItemSelector = ".item.flexserieslist"
    override val recommendedSectionSelector = "section.series-reco"

    override fun extractAnimePath(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val path = try {
            href.substringBefore("?").toHttpUrl().encodedPath
        } catch (_: Exception) {
            return null
        }
        return EP_URL_SUFFIX_REGEX.replace(path, "").takeIf { it.startsWith("/watch/") || it.startsWith("/anime/") }
    }

    override fun extractRelatedThumbnail(element: Element): String? = element.selectFirst("img")
        ?.let {
            it.attr("data-src")
                .ifBlank {
                    it.attr("src")
                }
        }

    // ======================= Related Anime Override =======================

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        return try {
            val document = response.asJsoup()
            val currentAnimePath = response.request.url.encodedPath
            val animeId = response.request.header("X-Anime-Id")
                ?: document.selectFirst("[data-id]")?.attr("data-id")
            val resultList = mutableListOf<SAnime>()

            if (!animeId.isNullOrBlank()) {
                try {
                    val listHeaders = headers.newBuilder().apply {
                        add("Accept", "application/json, text/javascript, */*; q=0.01")
                        add("Referer", response.request.url.toString())
                        add("X-Requested-With", "XMLHttpRequest")
                    }.build()

                    client.newCall(GET("$baseUrl/api/watch-order/$animeId", listHeaders)).execute().use { apiResponse ->
                        val relatedDoc = apiResponse.parseAs<ResultResponse>().toDocument()
                        relatedDoc.select(watchOrderItemSelector).forEach { element ->
                            val href = element.selectFirst("a[href]")?.attr("href") ?: return@forEach
                            val path = extractAnimePath(href) ?: return@forEach
                            if (path == currentAnimePath) return@forEach
                            val nameElement = element.selectFirst(".info .name") ?: return@forEach
                            resultList.add(
                                SAnime.create().apply {
                                    url = path
                                    title = getTitle(nameElement)
                                    thumbnail_url = extractRelatedThumbnail(element)
                                },
                            )
                        }
                    }
                } catch (_: Exception) { }
            }

            document.select(recommendedSectionSelector).firstOrNull {
                it.select("h2").text().contains("Recommended", ignoreCase = true)
            }?.select(".item")?.forEach { element ->
                val href = element.selectFirst("a[href]")?.attr("href") ?: return@forEach
                val path = extractAnimePath(href) ?: return@forEach
                if (path == currentAnimePath) return@forEach
                val nameElement = element.selectFirst(".info .name") ?: return@forEach
                resultList.add(
                    SAnime.create().apply {
                        url = path
                        title = getTitle(nameElement)
                        thumbnail_url = extractRelatedThumbnail(element)
                    },
                )
            }
            resultList.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ======================= Anime Details Override =======================

    override fun parseAnimeDetails(document: Document): SAnime {
        val newDocument = resolveSearchAnime(document)
        val titleElement = newDocument.selectFirst("h1.series-title")
        val animeId = newDocument.selectFirst("[data-id]")?.attr("data-id")

        return SAnime.create().apply {
            setUrlWithoutDomain(newDocument.location())
            if (!animeId.isNullOrBlank()) url += "#$animeId"
            title = getTitle(titleElement ?: newDocument.selectFirst("h1")!!)
            genre = newDocument.select(".series-genres .series-genre").joinToString { it.text() }
            author = newDocument.select(".series-fact:contains(Studios) .series-fact__value a").joinToString { it.text() }
            status = parseStatus(newDocument.select(".series-fact:contains(Status) .series-fact__value").text())
            description = buildDescription(newDocument, titleElement)

            newDocument.selectFirst(detailThumbnailSelector)?.let { img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }
                if (url.isNotEmpty()) thumbnail_url = url
            }
        }
    }

    override fun buildDescription(document: Document, titleElement: Element?): String = buildString {
        val enTitle = titleElement?.text()?.takeIf { it.isNotEmpty() }
        val jpTitle = titleElement?.attr("data-jp")?.trim()?.takeIf { it.isNotEmpty() }
        val score = document.select(".series-intro__poster .series-score b").text()

        val fancyScore = getFancyScore(score)
        if (scorePosition == SCORE_POS_TOP && fancyScore.isNotEmpty()) appendLine(fancyScore).appendLine()

        val synopsis = document.selectFirst(".series-blurb__full")
            ?: document.selectFirst(".series-blurb__short")
        synopsis?.text()?.let {
            appendLine(it).appendLine()
        }

        val meta = document.select(".series-facts__grid .series-fact").mapNotNull { div ->
            val label = div.selectFirst(".series-fact__label")?.text()?.removeSuffix(":") ?: ""
            val value = div.selectFirst(".series-fact__value")?.text() ?: ""

            if (label.isNotEmpty() && value.isNotEmpty() && label !in metaExclusionLabels) {
                "$label: $value"
            } else {
                null
            }
        }

        if (meta.isNotEmpty()) appendLine(meta.joinToString(" | ")).appendLine()

        val studios = document.select(".series-fact:contains(Studios) .series-fact__value a").joinToString { it.text() }
        val producers = document.select(".series-fact:contains(Producers) .series-fact__value a").joinToString { it.text() }

        when {
            studios.isNotEmpty() && producers.isNotEmpty() -> appendLine("**Studio:** $studios (**Producers:** $producers)").appendLine()
            studios.isNotEmpty() -> appendLine("**Studio:** $studios").appendLine()
            producers.isNotEmpty() -> appendLine("**Producers:** $producers").appendLine()
        }

        val altNames = mutableListOf<String>()
        if (useEnglish()) jpTitle?.let { altNames.add(it) } else enTitle?.let { altNames.add(it) }

        val nativeTitle = document.selectFirst(".series-native")?.text()
        if (nativeTitle != null && nativeTitle != jpTitle && nativeTitle != enTitle) {
            altNames.add(nativeTitle)
        }

        if (altNames.isNotEmpty()) appendLine("**Other name(s):** ${altNames.joinToString()}").appendLine()

        if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotEmpty()) append(fancyScore)
    }.trim()

    override fun parseStatus(statusString: String): Int = when (statusString.lowercase()) {
        "ongoing", "currently airing" -> SAnime.ONGOING
        "finished airing", "completed" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }
}
