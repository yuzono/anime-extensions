package eu.kanade.tachiyomi.animeextension.de.serienstream

import android.util.Base64
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
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMapNotNullBlocking
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.security.MessageDigest

class Serienstream :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Serienstream"

    override val baseUrl = "https://serienstream.to"

    override val lang = "de"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private var preferredHoster by preferences.delegate(SConstants.PREFERRED_HOSTER, SConstants.URL_STAPE)
    private var preferredLang by preferences.delegate(SConstants.PREFERRED_LANG, SConstants.LANG_GER_SUB)
    private var hosterSelection by preferences.delegate(SConstants.HOSTER_SELECTION, SConstants.HOSTER_NAMES.toSet())

    override val client = network.client.newBuilder()
        .addInterceptor(DdosGuardInterceptor(network.client))
        .build()

    private val json: Json by injectLazy()

    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }

    override fun popularAnimeSelector(): String = "a.show-card"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/beliebte-serien")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val rawHref = element.attr("href")
        val href = if (rawHref.contains("/staffel")) rawHref.substringBefore("/staffel") else rawHref
        anime.url = href.ifEmpty { rawHref }
        val img = element.selectFirst("img")
        anime.title = img?.attr("alt")?.takeIf { it.isNotBlank() } ?: element.text().trim()
        anime.thumbnail_url = img?.let { thumbUrl(it) }
        return anime
    }

    override fun latestUpdatesSelector(): String = "table.new-episodes-table tbody tr"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/neue-episoden")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val elements = document.select(latestUpdatesSelector())
        val animes = elements.mapNotNull { element ->
            val link = element.selectFirst("td a[href*=\"/serie/\"]") ?: return@mapNotNull null
            val href = link.attr("href")
            val serieHref = if (href.contains("/staffel")) href.substringBefore("/staffel") else href.substringBefore("/episode")
            SAnime.create().apply {
                url = serieHref
                title = link.text().trim()
            }
        }
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("td a[href*=\"/serie/\"]") ?: return anime
        val href = link.attr("href")
        val serieHref = if (href.contains("/staffel")) href.substringBefore("/staffel") else href.substringBefore("/episode")
        anime.url = serieHref
        anime.title = link.text().trim()
        return anime
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/api/search/suggest?term=$encoded", headers)
    }
    override fun searchAnimeSelector() = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.bodyString()
        return try {
            val obj = json.decodeFromString<JsonObject>(body)
            val shows = obj["shows"] as? JsonArray ?: JsonArray(emptyList())
            val animes = shows.parallelMapNotNullBlocking { elem ->
                val jo = elem.jsonObject
                val link = jo["url"]?.jsonPrimitive?.content ?: return@parallelMapNotNullBlocking null
                val title = jo["name"]?.jsonPrimitive?.content ?: return@parallelMapNotNullBlocking null
                val thumb = jo["image"]?.jsonPrimitive?.content ?: jo["cover"]?.jsonPrimitive?.content ?: jo["thumbnail"]?.jsonPrimitive?.content
                animeFromSearch(title, link, thumb?.let { UrlUtils.fixUrl(it, baseUrl) })
            }
            AnimesPage(animes, false)
        } catch (_: Exception) {
            try {
                val arr = json.decodeFromString<JsonArray>(body)
                val animes = arr.parallelMapNotNullBlocking { elem ->
                    val jo = elem.jsonObject
                    val link = jo["link"]?.jsonPrimitive?.content ?: return@parallelMapNotNullBlocking null
                    val title = jo["title"]?.jsonPrimitive?.content ?: return@parallelMapNotNullBlocking null
                    val thumb = jo["image"]?.jsonPrimitive?.content ?: jo["cover"]?.jsonPrimitive?.content ?: jo["thumbnail"]?.jsonPrimitive?.content
                    animeFromSearch(title, link, thumb?.let { UrlUtils.fixUrl(it, baseUrl) })
                }
                AnimesPage(animes, false)
            } catch (_: Exception) {
                AnimesPage(emptyList(), false)
            }
        }
    }

    private fun animeFromSearch(
        title: String,
        link: String,
        thumbnailUrl: String? = null,
    ): SAnime = SAnime.create().apply {
        this.title = title.replace("<em>", "").replace("</em>", "")
        url = link
        thumbnail_url = thumbnailUrl
    }

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore(" |")?.trim() ?: ""
        val img = document.selectFirst("div.show-cover-mobile img")
            ?: document.selectFirst("div.col-5 picture img")
            ?: document.selectFirst("img[data-src*=\"/media/images/channel/\"]")
            ?: document.selectFirst("picture img")
        anime.thumbnail_url = img?.let { thumbUrl(it) }
        anime.genre = document.select("a[href^=\"/genre/\"]").joinToString(", ") { it.text().trim() }
        anime.description = document.selectFirst("div.series-description span.description-text")?.text()?.trim()
            ?: document.selectFirst("div.series-description")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim() ?: ""
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
        anime.status = parseStatus(document)
        return anime
    }

    private fun parseStatus(document: Document): Int {
        val yearBlock = document.selectFirst("p.small.text-muted.mb-2")?.text() ?: return SAnime.UNKNOWN
        val yearRange = Regex("""((?:19|20)\d{2})\s*[-–]\s*((?:19|20)\d{2}|NA)""").find(yearBlock)
            ?: return SAnime.UNKNOWN
        return if (yearRange.groupValues[2] == "NA") SAnime.ONGOING else SAnime.COMPLETED
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val episodeList = mutableListOf<SEpisode>()
        var seasonLinks = document.select("nav#season-nav a[data-season-pill]")
        if (seasonLinks.isEmpty()) seasonLinks = document.select("a[data-season-pill]")
        if (seasonLinks.isEmpty()) seasonLinks = document.select("#stream > ul:nth-child(1) > li > a")
        if (seasonLinks.isEmpty()) {
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
                    client.newCall(GET(seasonHref)).execute().useAsJsoup()
                } catch (_: Exception) {
                    continue
                }
            }
            val rows = seasonDoc.select("table.episode-table tbody tr.episode-row")
            if (rows.isNotEmpty()) {
                rows.forEach { row -> episodeList.add(parseEpisodeRow(row, seasonHref)) }
            } else {
                val epLinks = seasonDoc.select("nav#episode-nav a[href*=\"/episode-\"]")
                epLinks.forEach { el ->
                    val ep = SEpisode.create()
                    val href = el.attr("abs:href")
                    ep.url = href.ifEmpty { el.attr("href") }.removePrefix(baseUrl)
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
        } else {
            episode.name = "Staffel $seasonNumRaw Folge $epNum : $displayTitle"
        }
        episode.episode_number = epNum.toFloat()
        return episode
    }

    override fun episodeFromElement(element: Element): SEpisode = parseEpisodeRow(element, element.attr("abs:href").substringBefore("/episode"))

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val formToken = document.selectFirst("input[name=_token]")?.attr("value") ?: csrfToken
        val buttons = document.select("button.link-box[data-play-url]")
            .ifEmpty { document.select("div.link-wrapper button[data-play-url]") }
            .ifEmpty { document.select("button[data-play-url]") }
        val selection = hosterSelection
        val altcha = try {
            fetchAltcha(response.request.url.toString(), csrfToken)
        } catch (_: Exception) {
            null
        }

        return buttons.parallelCatchingFlatMapBlocking { btn ->
            val provider = btn.attr("data-provider-name").trim()
            val langId = btn.attr("data-language-id").trim()
            val langLabel = btn.attr("data-language-label").trim()
            val playUrl = btn.attr("data-play-url").trim()
            if (playUrl.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()
            val language = getLanguage(langId) ?: getLanguage(langLabel) ?: langLabel
            val preFilterPass = when {
                selection.isEmpty() -> true
                provider.equals("VOE", true) -> selection.contains(SConstants.NAME_VOE)
                provider.equals("Doodstream", true) || provider.contains("Dood", true) -> selection.contains(SConstants.NAME_DOOD)
                provider.contains("Streamtape", true) -> selection.contains(SConstants.NAME_STAPE)
                provider.equals("Provider", true) -> true
                else -> selection.contains(provider)
            }
            if (!preFilterPass) return@parallelCatchingFlatMapBlocking emptyList()

            val rawT = if (playUrl.contains("t=")) playUrl.substringAfter("t=").substringBefore("&") else playUrl
            if (rawT.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()
            val t = try {
                java.net.URLDecoder.decode(rawT.replace("+", "%2B"), "UTF-8")
            } catch (_: Exception) {
                rawT
            }
            if (t.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()

            val hosterUrl = resolveHosterUrl(t, csrfToken, formToken, response.request.url.toString(), playUrl, altcha) ?: return@parallelCatchingFlatMapBlocking emptyList()
            if (hosterUrl.isBlank() || hosterUrl.contains("/r?") || hosterUrl == "$baseUrl/r" || hosterUrl == baseUrl) return@parallelCatchingFlatMapBlocking emptyList()

            if (provider.equals("Provider", true)) {
                val isVoe = hosterUrl.contains("voe", true)
                val isDood = hosterUrl.contains("dood", true) || hosterUrl.contains("myvidplay", true)
                val isStape = hosterUrl.contains("streamtape", true)
                when {
                    isVoe && !selection.contains(SConstants.NAME_VOE) -> return@parallelCatchingFlatMapBlocking emptyList()
                    isDood && !selection.contains(SConstants.NAME_DOOD) -> return@parallelCatchingFlatMapBlocking emptyList()
                    isStape && !selection.contains(SConstants.NAME_STAPE) -> return@parallelCatchingFlatMapBlocking emptyList()
                }
            }

            when {
                hosterUrl.contains("voe", true) && (selection.isEmpty() || selection.contains(SConstants.NAME_VOE)) -> voeExtractor.videosFromUrl(hosterUrl, language)
                hosterUrl.contains("dood", true) || hosterUrl.contains("myvidplay", true) -> {
                    if (selection.isEmpty() || selection.contains(SConstants.NAME_DOOD)) {
                        doodExtractor.videoFromUrl(hosterUrl, language)?.let(::listOf) ?: emptyList()
                    } else {
                        emptyList()
                    }
                }
                hosterUrl.contains("streamtape", true) -> {
                    if (selection.isEmpty() || selection.contains(SConstants.NAME_STAPE)) {
                        streamTapeExtractor.videoFromUrl(hosterUrl, "$language - Streamtape")?.let(::listOf) ?: emptyList()
                    } else {
                        emptyList()
                    }
                }
                provider.equals("VOE", true) -> voeExtractor.videosFromUrl(hosterUrl, language)
                else -> emptyList()
            }
        }
    }

    private fun resolveHosterUrl(t: String, csrfToken: String, formToken: String, referer: String, playUrl: String, altcha: String?): String? = try {
        val formBuilder = FormBody.Builder()
            .add("_token", formToken.ifEmpty { csrfToken })
            .add("t", t)
        if (!altcha.isNullOrBlank()) formBuilder.add("altcha", altcha)
        val formBody = formBuilder.build()
        val req = Request.Builder()
            .url("$baseUrl/r")
            .post(formBody)
            .addHeader("Referer", referer)
            .addHeader("X-CSRF-TOKEN", csrfToken)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Origin", baseUrl)
            .build()
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        noRedirectClient.newCall(req).execute().use { res ->
            var loc = res.header("Location")
            if (loc.isNullOrBlank()) {
                val body = res.bodyString()
                val meta = Regex("""url='([^']+)'""").find(body)?.groupValues?.get(1)
                    ?: Regex("""URL='([^']+)'""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
                loc = meta ?: res.request.url.toString()
                if (loc.contains("/r?") || loc == "$baseUrl/r") {
                    val play = UrlUtils.fixUrl(playUrl, baseUrl) ?: return null
                    client.newCall(GET(play)).execute().use { getRes ->
                        loc = getRes.header("Location") ?: getRes.request.url.toString()
                        if (loc.contains("/r?")) {
                            val b = getRes.bodyString()
                            loc = Regex("""url='([^']+)'""").find(b)?.groupValues?.get(1) ?: loc
                        }
                    }
                }
            }
            loc
        }
    } catch (_: Exception) {
        null
    }

    private fun fetchAltcha(referer: String, csrfToken: String): String? {
        val req = Request.Builder()
            .url("$baseUrl/api/inline/verify-init")
            .get()
            .addHeader("Referer", referer)
            .addHeader("X-CSRF-TOKEN", csrfToken)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .build()
        return client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.bodyString()
            val obj = json.decodeFromString<JsonObject>(body)
            val algorithm = obj["algorithm"]?.jsonPrimitive?.content ?: return null
            val challenge = obj["challenge"]?.jsonPrimitive?.content ?: return null
            val salt = obj["salt"]?.jsonPrimitive?.content ?: return null
            val signature = obj["signature"]?.jsonPrimitive?.content ?: return null
            val maxnumber = obj["maxnumber"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100000
            val number = solveAltcha(challenge, salt, algorithm, maxnumber) ?: return null
            val payload = buildJsonObject {
                put("algorithm", algorithm)
                put("challenge", challenge)
                put("salt", salt)
                put("signature", signature)
                put("number", number)
            }
            val jsonStr = json.encodeToString(JsonObject.serializer(), payload)
            Base64.encodeToString(jsonStr.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun solveAltcha(challenge: String, salt: String, algorithm: String, maxnumber: Int): Int? {
        val md = try {
            MessageDigest.getInstance(algorithm)
        } catch (_: Exception) {
            return null
        }
        for (i in 0..maxnumber) {
            val hash = md.digest((salt + i.toString()).toByteArray())
            val hex = hash.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            if (hex == challenge) return i
            md.reset()
        }
        return null
    }

    private fun thumbUrl(img: Element): String? {
        val url = img.attr("abs:data-src").ifBlank { img.attr("abs:src") }
            .ifBlank {
                val raw = img.attr("data-src").ifBlank { img.attr("src") }
                when {
                    raw.startsWith("http") -> raw
                    raw.isNotBlank() -> baseUrl + raw
                    else -> ""
                }
            }
        return url.ifBlank { null }
    }

    private val langMap = mapOf(
        "1" to SConstants.LANG_GER_DUB,
        "Deutsch" to SConstants.LANG_GER_DUB,
        "3" to SConstants.LANG_GER_SUB,
        "Ger-Sub" to SConstants.LANG_GER_SUB,
        "2" to SConstants.LANG_ENG_SUB,
        "Englisch" to SConstants.LANG_ENG_SUB,
        "English" to SConstants.LANG_ENG_SUB,
    )

    private fun getLanguage(key: String): String? {
        val k = key.trim()
        return langMap[k] ?: langMap.entries.firstOrNull { k.contains(it.key, true) }?.value
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferredHoster.takeIf { it.isNotBlank() }
        val hosterName = when (hoster) {
            SConstants.URL_VOE -> SConstants.NAME_VOE
            SConstants.URL_DOOD -> SConstants.NAME_DOOD
            SConstants.URL_STAPE -> SConstants.NAME_STAPE
            else -> hoster
        }
        val lang = preferredLang
        return sortedWith(
            compareByDescending<Video> { hosterName != null && (it.url.contains(hoster ?: "", true) || it.quality.contains(hosterName, true)) }
                .thenByDescending { it.quality.contains(lang, true) },
        )
    }

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = SConstants.PREFERRED_LANG,
            default = SConstants.LANG_GER_SUB,
            title = "Bevorzugte Sprache",
            entries = SConstants.LANGS.toList(),
            entryValues = SConstants.LANGS.toList(),
            summary = "%s",
        )
        screen.addListPreference(
            key = SConstants.PREFERRED_HOSTER,
            default = SConstants.URL_STAPE,
            title = "Standard-Hoster",
            entries = SConstants.HOSTER_NAMES.toList(),
            entryValues = SConstants.HOSTER_URLS.toList(),
            summary = "%s",
        )
        screen.addSetPreference(
            key = SConstants.HOSTER_SELECTION,
            default = SConstants.HOSTER_NAMES.toSet(),
            title = "Hoster auswählen",
            summary = "",
            entries = SConstants.HOSTER_NAMES.toList(),
            entryValues = SConstants.HOSTER_NAMES.toList(),
        )
    }
}
