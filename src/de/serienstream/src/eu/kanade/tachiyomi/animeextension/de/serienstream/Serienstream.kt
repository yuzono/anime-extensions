package eu.kanade.tachiyomi.animeextension.de.serienstream

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class Serienstream :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Serienstream"

    override val baseUrl = "https://s.to"

    override val lang = "de"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client = network.client.newBuilder()
        .addInterceptor(DdosGuardInterceptor(network.client))
        .build()

    private val json: Json by injectLazy()

    // ===== POPULAR ANIME =====
    override fun popularAnimeSelector(): String = "a.show-card"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/beliebte-serien")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val rawHref = element.attr("href")
        // Strip staffel suffix to get base serie url, fallback to raw
        val href = if (rawHref.contains("/staffel")) rawHref.substringBefore("/staffel") else rawHref
        anime.url = href.ifEmpty { rawHref }
        val img = element.selectFirst("img")
        anime.title = img?.attr("alt")?.takeIf { it.isNotBlank() } ?: element.text().trim()
        val thumb = img?.let {
            val ds = it.attr("data-src")
            if (ds.isNotBlank()) ds else it.attr("src")
        } ?: ""
        anime.thumbnail_url = when {
            thumb.startsWith("http") -> thumb
            thumb.isNotBlank() -> baseUrl + thumb
            else -> ""
        }
        return anime
    }

    // ===== LATEST ANIME =====
    override fun latestUpdatesSelector(): String = "table.new-episodes-table tbody tr"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/neue-episoden")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("td a[href*=\"/serie/\"]") ?: return anime
        val href = link.attr("href") // /serie/name/staffel-X/episode-Y
        val serieHref = if (href.contains("/staffel")) href.substringBefore("/staffel") else href.substringBefore("/episode")
        anime.url = serieHref
        anime.title = link.text().trim()
        // Try to fetch thumbnail from serie page (best effort)
        try {
            val doc = client.newCall(GET(baseUrl + serieHref)).execute().asJsoup()
            val img = doc.selectFirst("div.show-cover-mobile img")
                ?: doc.selectFirst("div.col-5 picture img")
                ?: doc.selectFirst("img[data-src*=\"/media/images/channel/\"]")
                ?: doc.selectFirst("picture img")
            val thumb = img?.let {
                val ds = it.attr("data-src")
                if (ds.isNotBlank()) ds else it.attr("src")
            } ?: ""
            anime.thumbnail_url = when {
                thumb.startsWith("http") -> thumb
                thumb.isNotBlank() -> baseUrl + thumb
                else -> ""
            }
        } catch (e: Exception) {
            anime.thumbnail_url = ""
        }
        return anime
    }

    // ===== SEARCH =====

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/api/search/suggest?term=$encoded", headers)
    }
    override fun searchAnimeSelector() = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        return try {
            val obj = json.decodeFromString<JsonObject>(body)
            val shows = obj["shows"] as? JsonArray ?: JsonArray(emptyList())
            val animes = shows.mapNotNull { elem ->
                val jo = elem.jsonObject
                val link = jo["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val title = jo["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                animeFromSearch(title, link)
            }
            AnimesPage(animes, false)
        } catch (e: Exception) {
            // Fallback: try old array format
            try {
                val arr = json.decodeFromString<JsonArray>(body)
                val animes = arr.mapNotNull { elem ->
                    val jo = elem.jsonObject
                    val link = jo["link"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val title = jo["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    animeFromSearch(title, link)
                }
                AnimesPage(animes, false)
            } catch (_: Exception) {
                AnimesPage(emptyList(), false)
            }
        }
    }

    private fun animeFromSearch(title: String, link: String): SAnime {
        val anime = SAnime.create()
        anime.title = title.replace("<em>", "").replace("</em>", "")
        anime.url = link
        try {
            val doc = client.newCall(GET(baseUrl + link)).execute().asJsoup()
            val img = doc.selectFirst("div.show-cover-mobile img")
                ?: doc.selectFirst("div.col-5 picture img")
                ?: doc.selectFirst("img[data-src*=\"/media/images/channel/\"]")
                ?: doc.selectFirst("picture img")
            val thumb = img?.let {
                val ds = it.attr("data-src")
                if (ds.isNotBlank()) ds else it.attr("src")
            } ?: ""
            anime.thumbnail_url = when {
                thumb.startsWith("http") -> thumb
                thumb.isNotBlank() -> baseUrl + thumb
                else -> ""
            }
        } catch (e: Exception) {
            anime.thumbnail_url = ""
        }
        return anime
    }

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    // ===== ANIME DETAILS =====
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore(" |")?.trim() ?: ""
        val img = document.selectFirst("div.show-cover-mobile img")
            ?: document.selectFirst("div.col-5 picture img")
            ?: document.selectFirst("img[data-src*=\"/media/images/channel/\"]")
            ?: document.selectFirst("picture img")
        val thumb = img?.let {
            val ds = it.attr("data-src")
            if (ds.isNotBlank()) ds else it.attr("src")
        } ?: ""
        anime.thumbnail_url = when {
            thumb.startsWith("http") -> thumb
            thumb.isNotBlank() -> baseUrl + thumb
            else -> ""
        }
        anime.genre = document.select("a[href^=\"/genre/\"]").joinToString(", ") { it.text().trim() }
        anime.description = document.selectFirst("div.series-description span.description-text")?.text()?.trim()
            ?: document.selectFirst("div.series-description")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim() ?: ""
        // Try chip-based author (episode page) then serie page fallback
        val producersChip = document.select("[id^=pg-produzenten-] a").eachText()
        val directorsChip = document.select("[id^=pg-regisseure-] a").eachText()
        val actorsChip = document.select("[id^=pg-besetzung-] a").eachText()
        val producersSerie = document.select("li.series-group:has(strong:contains(Produzent)) a").eachText()
        val directorsSerie = document.select("li.series-group:has(strong:contains(Regisseur)) a").eachText()
        val actorsSerie = document.select("li.series-group:has(strong:contains(Besetzung)) a").eachText()
        val author = when {
            producersChip.isNotEmpty() -> producersChip.joinToString(", ")
            directorsChip.isNotEmpty() -> directorsChip.joinToString(", ")
            actorsChip.isNotEmpty() -> actorsChip.joinToString(", ")
            producersSerie.isNotEmpty() -> producersSerie.joinToString(", ")
            directorsSerie.isNotEmpty() -> directorsSerie.joinToString(", ")
            actorsSerie.isNotEmpty() -> actorsSerie.joinToString(", ")
            else -> document.select("div.chips-wrap a").joinToString(", ") { it.text().trim() }
        }
        if (author.isNotBlank()) anime.author = author
        anime.status = SAnime.UNKNOWN
        return anime
    }

    // ===== EPISODE =====
    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        var seasonLinks = document.select("nav#season-nav a[data-season-pill]")
        if (seasonLinks.isEmpty()) seasonLinks = document.select("a[data-season-pill]")
        if (seasonLinks.isEmpty()) seasonLinks = document.select("#stream > ul:nth-child(1) > li > a")
        if (seasonLinks.isEmpty()) {
            // Single season page: parse table directly
            val rows = document.select("table.episode-table tbody tr.episode-row")
            if (rows.isNotEmpty()) {
                val baseSeasonUrl = response.request.url.toString()
                rows.forEach { row -> episodeList.add(parseEpisodeRow(row, baseSeasonUrl)) }
                return episodeList.reversed()
            }
            return emptyList()
        }
        val baseRequestUrl = response.request.url.toString()
        for (seasonLink in seasonLinks) {
            val seasonHref = seasonLink.attr("abs:href")
            if (seasonHref.isBlank()) continue
            val seasonDoc = if (seasonHref == baseRequestUrl) {
                document
            } else {
                try {
                    client.newCall(GET(seasonHref)).execute().asJsoup()
                } catch (e: Exception) {
                    continue
                }
            }
            val rows = seasonDoc.select("table.episode-table tbody tr.episode-row")
            if (rows.isNotEmpty()) {
                rows.forEach { row -> episodeList.add(parseEpisodeRow(row, seasonHref)) }
            } else {
                // Fallback to nav episode links
                val epLinks = seasonDoc.select("nav#episode-nav a[href*=\"/episode-\"]")
                epLinks.forEach { el ->
                    val ep = SEpisode.create()
                    val href = el.attr("abs:href")
                    ep.url = href.ifEmpty { el.attr("href") }
                    val epNum = el.text().trim().toIntOrNull() ?: 1
                    val seasonNum = seasonHref.substringAfter("/staffel-").substringBefore("/").ifEmpty { "1" }
                    ep.name = "Staffel $seasonNum Folge $epNum"
                    ep.episode_number = epNum.toFloat()
                    episodeList.add(ep)
                }
            }
        }
        return episodeList.reversed()
    }

    private fun parseEpisodeRow(element: Element, seasonUrl: String): SEpisode {
        val episode = SEpisode.create()
        val onclick = element.attr("onclick")
        val href = when {
            onclick.isNotBlank() -> {
                Regex("""window\.location='([^']+)'""").find(onclick)?.groupValues?.get(1) ?: ""
            }
            else -> element.selectFirst("a[href*=\"/episode-\"]")?.attr("href") ?: ""
        }
        episode.url = href
        val seasonNumRaw = seasonUrl.substringAfter("/staffel-").substringBefore("/").substringBefore("?").ifEmpty { "1" }
        val isFilm = seasonNumRaw == "0" || seasonUrl.contains("/staffel-0")
        val epNumText = element.selectFirst("th.episode-number-cell")?.text()?.trim()
            ?: Regex("""episode-(\d+)""").find(href)?.groupValues?.get(1) ?: "1"
        val epNum = epNumText.toIntOrNull() ?: 1
        val titleGer = element.selectFirst("strong.episode-title-ger")?.text()?.trim()
            ?: element.selectFirst("td.episode-title-cell strong")?.text()?.trim() ?: ""
        val titleEng = element.selectFirst("span.episode-title-eng")?.text()?.trim() ?: ""
        val displayTitle = when {
            titleGer.isNotBlank() -> titleGer
            titleEng.isNotBlank() -> titleEng
            else -> "Episode $epNum"
        }
        if (isFilm) {
            episode.name = "Film $epNum : $displayTitle"
            episode.episode_number = epNum.toFloat()
        } else {
            episode.name = "Staffel $seasonNumRaw Folge $epNum : $displayTitle"
            // Use absolute episode number for sorting; keep simple
            episode.episode_number = try {
                // Try to keep unique ordering across seasons: season*1000 + ep
                val s = seasonNumRaw.toIntOrNull() ?: 1
                (s * 1000 + epNum).toFloat()
            } catch (_: Exception) {
                epNum.toFloat()
            }
        }
        return episode
    }

    override fun episodeFromElement(element: Element): SEpisode {
        // Keep for compatibility, parse as generic row
        return parseEpisodeRow(element, element.attr("abs:href").substringBefore("/episode"))
    }

    // ===== VIDEO SOURCES =====
    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val formToken = document.selectFirst("input[name=_token]")?.attr("value") ?: csrfToken
        val buttons = document.select("button.link-box[data-play-url]")
            .ifEmpty { document.select("div.link-wrapper button[data-play-url]") }
            .ifEmpty { document.select("button[data-play-url]") }
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet(SConstants.HOSTER_SELECTION, null)

        for (btn in buttons) {
            val provider = btn.attr("data-provider-name").trim()
            val langId = btn.attr("data-language-id").trim()
            val langLabel = btn.attr("data-language-label").trim()
            val playUrl = btn.attr("data-play-url").trim() // /r?t=...
            if (playUrl.isBlank()) continue
            val language = getLanguage(langId) ?: getLanguage(langLabel) ?: langLabel
            // Pre-filter by provider if possible
            val preFilterPass = when {
                hosterSelection == null || hosterSelection.isEmpty() -> true
                provider.equals("VOE", ignoreCase = true) -> hosterSelection.contains(SConstants.NAME_VOE)
                provider.equals("Doodstream", ignoreCase = true) || provider.contains("Dood", ignoreCase = true) -> hosterSelection.contains(SConstants.NAME_DOOD)
                provider.contains("Streamtape", ignoreCase = true) -> hosterSelection.contains(SConstants.NAME_STAPE)
                provider.equals("Provider", ignoreCase = true) -> true // need to resolve to decide
                else -> hosterSelection.contains(provider)
            }
            if (!preFilterPass) continue

            val t = when {
                playUrl.contains("t=") -> playUrl.substringAfter("t=").substringBefore("&")
                else -> playUrl
            }
            if (t.isBlank()) continue

            // Resolve hoster URL via POST to /r
            val hosterUrl = try {
                val formBody = FormBody.Builder()
                    .add("_token", formToken.ifEmpty { csrfToken })
                    .add("t", t)
                    .build()
                val req = Request.Builder()
                    .url("$baseUrl/r")
                    .post(formBody)
                    .addHeader("Referer", response.request.url.toString())
                    .addHeader("X-CSRF-TOKEN", csrfToken)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .addHeader("Origin", baseUrl)
                    .build()
                val res = client.newCall(req).execute()
                // Try Location header first
                var loc = res.header("Location")
                if (loc.isNullOrBlank()) {
                    // Try to parse meta refresh from body
                    val body = res.body.string()
                    val meta = Regex("""url='([^']+)'""").find(body)?.groupValues?.get(1)
                        ?: Regex("""URL='([^']+)'""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
                    loc = meta ?: res.request.url.toString()
                    // If still /r, fallback to request url
                    if (loc.contains("/r?") || loc == "$baseUrl/r") {
                        // Try GET fallback
                        val getRes = client.newCall(GET(baseUrl + playUrl)).execute()
                        loc = getRes.header("Location") ?: getRes.request.url.toString()
                        if (loc.contains("/r?")) {
                            val b = getRes.body.string()
                            loc = Regex("""url='([^']+)'""").find(b)?.groupValues?.get(1) ?: loc
                        }
                    }
                }
                res.close()
                loc ?: ""
            } catch (e: Exception) {
                continue
            }
            if (hosterUrl.isBlank() || hosterUrl.contains("/r?") || hosterUrl == "$baseUrl/r" || hosterUrl == baseUrl) continue
            // Post-filter for generic Provider after resolving
            if (hosterSelection != null && hosterSelection.isNotEmpty() && provider.equals("Provider", ignoreCase = true)) {
                val isVoe = hosterUrl.contains("voe", ignoreCase = true)
                val isDood = hosterUrl.contains("dood", ignoreCase = true) || hosterUrl.contains("myvidplay", ignoreCase = true)
                val isStape = hosterUrl.contains("streamtape", ignoreCase = true)
                when {
                    isVoe && !hosterSelection.contains(SConstants.NAME_VOE) -> continue
                    isDood && !hosterSelection.contains(SConstants.NAME_DOOD) -> continue
                    isStape && !hosterSelection.contains(SConstants.NAME_STAPE) -> continue
                    !isVoe && !isDood && !isStape -> {
                        // Unknown hoster, skip if user filtered
                        // If user selected all, we will try to include via generic handling below
                    }
                }
            }
            // Extract via appropriate extractor
            when {
                hosterUrl.contains("voe", ignoreCase = true) && (hosterSelection == null || hosterSelection.contains(SConstants.NAME_VOE)) -> {
                    val vids = try {
                        VoeExtractor(client, headers).videosFromUrl(hosterUrl, "($language) ")
                    } catch (_: Exception) {
                        emptyList()
                    }
                    videoList.addAll(vids)
                }
                (hosterUrl.contains("dood", ignoreCase = true) || hosterUrl.contains("myvidplay", ignoreCase = true)) && (hosterSelection == null || hosterSelection.contains(SConstants.NAME_DOOD)) -> {
                    val quality = "Doodstream $language"
                    try {
                        val v = DoodExtractor(client).videoFromUrl(hosterUrl, quality)
                        if (v != null) videoList.add(v)
                    } catch (_: Exception) {}
                }
                hosterUrl.contains("streamtape", ignoreCase = true) && (hosterSelection == null || hosterSelection.contains(SConstants.NAME_STAPE)) -> {
                    val quality = "Streamtape $language"
                    try {
                        val v = StreamTapeExtractor(client).videoFromUrl(hosterUrl, quality)
                        if (v != null) videoList.add(v)
                    } catch (_: Exception) {}
                }
                else -> {
                    // Fallback: try VOE if provider was VOE
                    if (provider.equals("VOE", ignoreCase = true) && (hosterSelection == null || hosterSelection.contains(SConstants.NAME_VOE))) {
                        try {
                            videoList.addAll(VoeExtractor(client, headers).videosFromUrl(hosterUrl, "($language) "))
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        return videoList
    }

    private fun getLanguage(langKey: String): String? {
        val k = langKey.trim()
        return when {
            k == SConstants.KEY_GER_SUB.toString() || k.equals("Ger-Sub", ignoreCase = true) || k.equals("3", ignoreCase = true) -> SConstants.LANG_GER_SUB
            k == SConstants.KEY_GER_DUB.toString() || k.equals("Deutsch", ignoreCase = true) || k.equals("1", ignoreCase = true) -> SConstants.LANG_GER_DUB
            k == SConstants.KEY_ENG_SUB.toString() || k.equals("Englisch", ignoreCase = true) || k.equals("English", ignoreCase = true) || k.equals("2", ignoreCase = true) -> SConstants.LANG_ENG_SUB
            k.contains(SConstants.KEY_GER_SUB.toString()) -> SConstants.LANG_GER_SUB
            k.contains(SConstants.KEY_GER_DUB.toString()) -> SConstants.LANG_GER_DUB
            k.contains(SConstants.KEY_ENG_SUB.toString()) -> SConstants.LANG_ENG_SUB
            else -> null
        }
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(SConstants.PREFERRED_HOSTER, null)
        val subPreference = preferences.getString(SConstants.PREFERRED_LANG, "Sub")!!
        val hosterList = mutableListOf<Video>()
        val otherList = mutableListOf<Video>()
        if (hoster != null) {
            for (video in this) {
                if (video.url.contains(hoster)) {
                    hosterList.add(video)
                } else {
                    otherList.add(video)
                }
            }
        } else {
            otherList += this
        }
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in hosterList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        for (video in otherList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }

        return newList
    }

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ===== PREFERENCES ======
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = SConstants.PREFERRED_HOSTER
            title = "Standard-Hoster"
            entries = SConstants.HOSTER_NAMES
            entryValues = SConstants.HOSTER_URLS
            setDefaultValue(SConstants.URL_STAPE)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subPref = ListPreference(screen.context).apply {
            key = SConstants.PREFERRED_LANG
            title = "Bevorzugte Sprache"
            entries = SConstants.LANGS
            entryValues = SConstants.LANGS
            setDefaultValue(SConstants.LANG_GER_SUB)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val hosterSelection = MultiSelectListPreference(screen.context).apply {
            key = SConstants.HOSTER_SELECTION
            title = "Hoster auswählen"
            entries = SConstants.HOSTER_NAMES
            entryValues = SConstants.HOSTER_NAMES
            setDefaultValue(SConstants.HOSTER_NAMES.toSet())

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(subPref)
        screen.addPreference(hosterPref)
        screen.addPreference(hosterSelection)
    }
}
