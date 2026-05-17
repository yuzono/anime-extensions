package eu.kanade.tachiyomi.animeextension.en.kotokai

import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme.VideoData
import org.jsoup.nodes.Document

class KotoKai :
    AnikotoTheme(
        "en",
        "AnimeKai (Unoriginal)",
        domainEntries = listOf(
            "animekaitv.to",
            "anikaitv.to",
            "animekai.se",
            "anikai.se",
        ),
        hosterNames = listOf("megaplay", "vidstream", "vidcloud", "kiwi-stream"),
        hosterDisplayNames = listOf("MegaPlay", "Vidstream", "VidCloud", "Kiwi-Stream"),
    ) {

    // =================== Video Server List Override ======================
    // Server display names are "HD-1", "HD-2" or "Kiwi-Stream-360p" etc.
    // Hoster info is in data-sv-id: "323" = vidstream, "xtp" = kiwi-stream
    // Duplicate SUB sections exist — deduplicate by serverId

    override fun parseServerListData(
        document: Document,
        typeSelection: Set<String>,
        serverNumSelection: Set<String>,
    ): List<VideoData> {
        val seen = mutableSetOf<String>()

        return document.select("div.type").flatMap { typeElem ->
            val label = typeElem.selectFirst("label")?.text()?.trim()?.let { lbl ->
                when (lbl.uppercase()) {
                    "SUB" -> "Sub"
                    "H-SUB" -> "H-Sub"
                    "DUB" -> "Dub"
                    "A-DUB" -> "A-Dub"
                    else -> lbl.replaceFirstChar { it.uppercase() }
                }
            } ?: typeElem.attr("data-type").replaceFirstChar { it.uppercase() }

            if (!typeSelection.contains(label, true)) return@flatMap emptyList<VideoData>()

            typeElem.select("li").mapNotNull { serverElement ->
                val serverId = serverElement.attr("data-link-id")
                if (serverId.isBlank()) return@mapNotNull null
                if (!seen.add(serverId)) return@mapNotNull null // deduplicate

                val svId = serverElement.attr("data-sv-id")
                val hosterName = mapSvIdToHoster(svId)
                if (hostToggle.none { hosterName.contains(it, true) }) return@mapNotNull null

                val spanText = serverElement.text().trim().lowercase()
                val (serverNum, qualitySuffix) = parseServerName(spanText)
                if (!serverNumSelection.contains(serverNum.toString())) return@mapNotNull null

                val serverName = "$hosterName-$serverNum$qualitySuffix"

                VideoData(label, serverId, serverName)
            }
        }
    }

    private fun parseServerName(text: String): Pair<Int, String> = when {
        text.startsWith("hd-") -> Pair(text.substringAfter("hd-").toIntOrNull() ?: 1, "")
        text.startsWith("kiwi-stream-") -> {
            val suffix = text.substringAfter("kiwi-stream-")
            val qualityMatch = Regex("""(\d+)p$""").find(suffix)
            if (qualityMatch != null) {
                Pair(1, "-${qualityMatch.value}")
            } else {
                Pair(suffix.toIntOrNull() ?: 1, "")
            }
        }
        text.startsWith("server-") -> {
            val suffix = text.substringAfter("server-")
            Pair(1, "-$suffix")
        }
        else -> Pair(text.toIntOrNull() ?: 1, "")
    }

    private fun mapSvIdToHoster(svId: String): String = when (svId) {
        "323" -> "vidstream"
        "xtp" -> "kiwi-stream"
        else -> svId.lowercase()
    }
}
