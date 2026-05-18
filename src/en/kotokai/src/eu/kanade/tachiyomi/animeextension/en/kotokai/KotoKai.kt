package eu.kanade.tachiyomi.animeextension.en.kotokai

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoThemeFilters
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoThemeFilters.addListQueryParameter
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoThemeFilters.addQueryParameterIfNotEmpty
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
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
        hosterNames = listOf("megaplay", "vidstream", "kiwi-stream"),
        hosterDisplayNames = listOf("MegaPlay", "Vidstream", "Kiwi-Stream"),
    ) {

    // =================== Video Server List Override ======================

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
                if (!seen.add(serverId)) return@mapNotNull null

                val svId = serverElement.attr("data-sv-id")
                val spanText = serverElement.text().trim().lowercase()

                val hosterName = mapSvIdToHoster(svId, spanText)
                if (hostToggle.none { hosterName.contains(it, true) }) return@mapNotNull null

                val serverNum = parseServerNum(spanText)
                if (!serverNumSelection.contains(serverNum.toString())) return@mapNotNull null

                val serverName = buildServerName(spanText, svId)

                VideoData(label, serverId, serverName)
            }
        }
    }

    private fun buildServerName(text: String, svId: String): String = when (svId) {
        "323" -> when {
            text.startsWith("hd-1") -> "megaplay-1"
            text.startsWith("hd-2") -> "vidstream-1"
            text.startsWith("hd-") -> "vidstream-" + text.substringAfter("hd-")
            else -> "vidstream-1"
        }
        "xtp" -> when {
            text.startsWith("kiwi-stream-") -> "kiwi-stream-1-" + text.substringAfter("kiwi-stream-")
            else -> "kiwi-stream-1"
        }
        else -> text.lowercase().replace(" ", "-")
    }

    private fun mapSvIdToHoster(svId: String, text: String): String = when (svId) {
        "323" -> when {
            text.startsWith("hd-1") -> "megaplay"
            text.startsWith("hd-2") -> "vidstream"
            else -> "vidstream"
        }
        "xtp" -> "kiwi-stream"
        else -> svId.lowercase()
    }

    private fun parseServerNum(text: String): Int = when {
        text.startsWith("hd-") -> text.substringAfter("hd-").toIntOrNull() ?: 1
        else -> 1
    }

    // =================== Search Request Override ======================

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
            addListQueryParameter("term_type", params.types) // Fixed: was "type"
            addListQueryParameter("status", params.statuses) // Fixed: was addQueryParameterIfNotEmpty
            addListQueryParameter("language", params.languages) // Fixed: was addQueryParameterIfNotEmpty
            addListQueryParameter("rating", params.ratings) // Fixed: was addQueryParameterIfNotEmpty
            addQueryParameterIfNotEmpty("sort", params.sort)
        }.build().toString()

        return GET(url, docHeaders)
    }
}
