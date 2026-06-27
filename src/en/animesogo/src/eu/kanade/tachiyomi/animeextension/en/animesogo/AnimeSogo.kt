package eu.kanade.tachiyomi.animeextension.en.animesogo

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoThemeFilters
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoThemeFilters.addListQueryParameter
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoThemeFilters.addQueryParameterIfNotEmpty
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeSogo :
    AnikotoTheme(
        "en",
        "AnimeSogo",
        domainEntries = listOf(
            "animesogo.to",
        ),
        hosterNames = listOf("HD-1", "HD-2", "HD-3", "VidPlay-1", "Kiwi-Stream"),
    ) {

    // =================== Selector Overrides ==============================

    override val metaContainerSelector = "div.bl-meta"
    override val scoreLabelName = "Scores"
    override val aliasContainerSelector = "div.alias"
    override val metaExclusionLabels = listOf("Genres", "Status", "Studios", "Producers", "Scores")
    override val recommendedSectionSelector = "aside.sidebar section"
    override val synopsisContentSelector = "div.synopsis > div.content"
    override val detailThumbnailSelector = "section#w-info div.poster img"
    override val listingThumbnailSelector = "a.poster img"
    override val watchOrderItemSelector = "a.item"

    // =================== Episode List Override ===========================

    override fun episodeListSelector() = "ul.episodes > li > a"

    // =================== Server Name Mapping =============================

    override fun extractBaseServerName(rawName: String): String {
        val base = super.extractBaseServerName(rawName)
        return when (base) {
            "Server" -> "Kiwi-Stream"
            else -> base
        }
    }

    override fun getServerDisplayName(serverName: String): String = when {
        serverName.startsWith("Server", true) -> {
            val suffix = serverName.substringAfter("Server", "").trim()
            "Kiwi-Stream" + if (suffix.isNotEmpty()) " $suffix" else ""
        }
        else -> serverName.trimEnd('-', ' ')
    }

    // =================== Video Server List Override ======================

    override fun parseServerListData(document: Document): List<VideoData> {
        val typeElements = document.select("div.type")

        typeElements.flatMap { elem ->
            elem.select("a.server")
                .mapNotNull { it.selectFirst("span")?.text()?.trim()?.takeIf(String::isNotBlank) }
                .map { getServerDisplayName(it) }
        }.also { updateDiscoveredServers(it, isMapper = false) }

        val effectiveTypeToggle = typeToggle
        val effectiveHostToggle = hostToggle

        return typeElements.flatMap { elem ->
            val label = resolveTypeLabel(elem)

            if (!isTypeEnabled(label, effectiveTypeToggle)) return@flatMap emptyList()

            elem.select("a.server").mapNotNull { serverElem ->
                val serverId = serverElem.attr("data-link-id")
                if (serverId.isBlank()) return@mapNotNull null

                val rawName = serverElem.selectFirst("span")?.text()?.trim() ?: return@mapNotNull null
                val serverName = getServerDisplayName(rawName)

                if (!effectiveHostToggle.contains(serverName, true)) return@mapNotNull null

                VideoData(label, serverId, serverName)
            }
        }
    }

    // =================== Search Override =================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnikotoThemeFilters.getSearchParameters(filters)
        val vrf = if (query.isNotBlank()) vrfEncrypt(query) else ""

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("filter")
            addQueryParameter("keyword", query)
            addQueryParameter("page", page.toString())
            addQueryParameter("vrf", vrf)

            addListQueryParameter("genre", params.genres)
            addListQueryParameter("season", params.seasons)
            addListQueryParameter("year", params.years)
            addListQueryParameter("term_type", params.types)
            addListQueryParameter("status", params.statuses)
            addListQueryParameter("language", params.languages)
            addListQueryParameter("rating", params.ratings.map { it.lowercase().replace("-", "_") })
            addQueryParameterIfNotEmpty("sort", params.sort)
        }.build().toString()

        return GET(url, docHeaders, cacheControl)
    }

    // =================== Thumbnail Override ==============================

    override fun extractRelatedThumbnail(element: Element): String? {
        val style = element.selectFirst("[style*='background-image']")?.attr("style") ?: return null
        return BACKGROUND_IMAGE_URL_REGEX.find(style)?.groupValues?.get(1)
    }

    companion object {
        private val BACKGROUND_IMAGE_URL_REGEX =
            Regex("""background-image\s*:\s*url\(['"]?([^'")\s]+)['"]?\)""")
    }
}
