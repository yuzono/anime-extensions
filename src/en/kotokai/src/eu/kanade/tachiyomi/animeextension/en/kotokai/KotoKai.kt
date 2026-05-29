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
import org.jsoup.nodes.Element

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
        hosterNames = listOf("MegaPlay", "Vidstream", "VidCloud", "Kiwi-Stream"),
        hosterDisplayNames = listOf("HD-1", "Vidstream", "VidCloud", "Kiwi-Stream"),
    ) {

    // =================== Video Server List Override ======================

    override fun parseServerListData(
        document: Document,
        typeSelection: Set<String>,
    ): List<VideoData> {
        return document.select("div.servers > div.type").flatMap { typeElem ->
            val typeLabel = resolveTypeLabel(typeElem)

            if (!isTypeEnabled(typeLabel, typeSelection)) return@flatMap emptyList()

            typeElem.select("li").mapNotNull { serverElem ->
                if (serverElem.hasClass("download-icon")) return@mapNotNull null

                val serverId = serverElem.attr("data-link-id")
                if (serverId.isBlank()) return@mapNotNull null

                val serverName = serverElem.text().trim()
                val hoster = mapServerToHoster(serverName) ?: return@mapNotNull null
                if (hoster !in hostToggle) return@mapNotNull null

                VideoData(typeLabel, serverId, serverName)
            }
        }
    }

    private fun mapServerToHoster(serverName: String): String? = when {
        serverName.startsWith("HD-", true) -> "MegaPlay"
        serverName.startsWith("Vidstream", true) -> "Vidstream"
        serverName.startsWith("VidCloud", true) -> "VidCloud"
        serverName.startsWith("Kiwi-Stream", true) -> "Kiwi-Stream"
        else -> null
    }

    private fun resolveTypeLabel(typeElem: Element): String {
        val labelText = typeElem.selectFirst("label")?.text()?.trim()
        val dataType = typeElem.attr("data-type")

        return when {
            labelText.equals("SUB", true) -> "Sub"
            labelText.equals("H-SUB", true) -> "S-Sub"
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

    override fun getServerDisplayName(serverName: String): String = when (serverName) {
        "MegaPlay" -> "HD-"
        "Vidstream" -> "Vidstream-"
        "VidCloud" -> "VidCloud-"
        "Kiwi-Stream" -> "Kiwi-Stream"
        else -> super.getServerDisplayName(serverName)
    }

    // =================== Search Request Override =========================

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
            addListQueryParameter("rating", params.ratings)
            addQueryParameterIfNotEmpty("sort", params.sort)
        }.build().toString()

        return GET(url, docHeaders)
    }
}
