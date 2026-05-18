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
        hosterNames = listOf("megaplay", "vidstream", "vidcloud", "kiwi-stream"),
        hosterDisplayNames = listOf("MegaPlay", "Vidstream", "VidCloud", "Kiwi-Stream"),
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
        serverNumSelection: Set<String>,
    ): List<VideoData> {
        return document.select("div.type").flatMap { typeElem ->
            val typeLabel = resolveTypeLabel(typeElem)
            if (!typeSelection.contains(typeLabel, true)) return@flatMap emptyList<VideoData>()

            typeElem.select("a.server").mapNotNull { serverElem ->
                val serverId = serverElem.attr("data-link-id")
                if (serverId.isBlank()) return@mapNotNull null

                val hosterName = mapSvIdToHoster(serverElem.attr("data-sv-id"))
                if (hostToggle.none { hosterName.contains(it, true) }) return@mapNotNull null

                val spanText = serverElem.selectFirst("span")?.text()?.trim()?.lowercase() ?: ""
                val serverNum = resolveServerNumber(spanText)
                if (!serverNumSelection.contains(serverNum.toString())) return@mapNotNull null

                val qualitySuffix = if (spanText.startsWith("server-")) {
                    "-" + spanText.substringAfter("server-")
                } else {
                    ""
                }
                val serverName = "$hosterName-$serverNum$qualitySuffix"

                VideoData(typeLabel, serverId, serverName)
            }
        }
    }

    private fun mapSvIdToHoster(svId: String): String = when (svId) {
        "323" -> "vidstream"
        "fbx" -> "kiwi-stream"
        else -> svId.lowercase()
    }

    private fun resolveTypeLabel(typeElem: Element): String {
        val labelText = typeElem.selectFirst("label")?.text()?.trim()
        val dataType = typeElem.attr("data-type")

        return when {
            labelText.equals("SUB", true) -> "Sub"
            labelText.equals("HSUB", true) || labelText.equals("S-Sub", true) -> "H-Sub"
            labelText.equals("DUB", true) -> "Dub"
            labelText.equals("A-DUB", true) -> "A-Dub"
            dataType.equals("sub", true) -> "Sub"
            dataType.equals("hsub", true) -> "H-Sub"
            dataType.equals("dub", true) -> "Dub"
            dataType.equals("adub", true) -> "A-Dub"
            else -> (labelText ?: dataType).replaceFirstChar { it.uppercase() }
        }
    }

    private fun resolveServerNumber(spanText: String): Int = when {
        spanText.startsWith("hd-") -> spanText.substringAfter("hd-").toIntOrNull() ?: 1
        spanText.startsWith("server-") -> 1
        else -> spanText.toIntOrNull() ?: 1
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
