package eu.kanade.tachiyomi.animeextension.en.animesogo

import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeSogo :
    AnikotoTheme(
        "en",
        "AnimeSogo",
        domainEntries = listOf(
            "animesogo.to",
        ),
        hosterNames = listOf("HD-1", "HD-2", "HD-3", "Server-"),
        hosterDisplayNames = listOf("HD-1", "HD-2", "HD-3", "Kiwi-Stream"),
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

    // =================== Episode List Override ===========================

    override fun episodeListSelector() = "ul.episodes > li > a"

    // =================== Video Server List Override ======================

    override fun parseServerListData(
        document: Document,
        typeSelection: Set<String>,
    ): List<VideoData> {
        return document.select("div.type").flatMap { typeElem ->
            val typeLabel = resolveTypeLabel(typeElem)

            if (!isTypeEnabled(typeLabel, typeSelection)) return@flatMap emptyList()

            typeElem.select("a.server").mapNotNull { serverElem ->
                val serverId = serverElem.attr("data-link-id")
                if (serverId.isBlank()) return@mapNotNull null

                val serverName = serverElem.selectFirst("span")?.text()?.trim() ?: return@mapNotNull null
                if (serverName !in hostToggle) return@mapNotNull null

                VideoData(typeLabel, serverId, serverName)
            }
        }
    }

    private fun resolveTypeLabel(typeElem: Element): String {
        val labelText = typeElem.selectFirst("label")?.text()?.trim()
        val dataType = typeElem.attr("data-type")

        return when {
            labelText.equals("SUB", true) -> "Sub"
            labelText.equals("S-SUB", true) -> "S-Sub"
            labelText.equals("HSUB", true) -> "HSub"
            labelText.equals("DUB", true) -> "Dub"
            labelText.equals("A-DUB", true) -> "A-Dub"
            dataType.equals("sub", true) -> "Sub"
            dataType.equals("hsub", true) -> "HSub"
            dataType.equals("dub", true) -> "Dub"
            dataType.equals("adub", true) -> "A-Dub"
            else -> (labelText ?: dataType).replaceFirstChar { it.uppercase() }
        }
    }

    override fun mapMapperServerName(key: String): String = when {
        key.startsWith("Kiwi-Stream", true) -> "Server-"
        else -> super.mapMapperServerName(key)
    }

    override fun getServerDisplayName(serverName: String): String = when (serverName) {
        "Server-" -> "Kiwi-Stream"
        else -> super.getServerDisplayName(serverName)
    }

    // =================== Thumbnail Override ==============================

    override fun extractRelatedThumbnail(element: Element): String? {
        val style = element.selectFirst("[style*='background-image']")?.attr("style") ?: return null
        return BACKGROUND_IMAGE_URL_REGEX.find(style)?.groupValues?.get(1)
    }

    override val watchOrderItemSelector = "a.item"

    companion object {
        private val BACKGROUND_IMAGE_URL_REGEX =
            Regex("""background-image\s*:\s*url\(['"]?([^'")\s]+)['"]?\)""")
    }
}
