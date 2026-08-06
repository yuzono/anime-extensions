package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class Anime3rb :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Anime3rb"

    override val baseUrl = "https://anime3rb.com"

    override val client = network.client.newBuilder()
        .addNetworkInterceptor(CloudflareInterceptor(network.client))
        .build()

    override val lang = "ar"

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("referer", baseUrl)

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/titles/list?page=$page", headers)

    override fun popularAnimeSelector(): String = "div.title-card > a[href*='/titles/']:not(.details)"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val href = element.absUrl("href").takeUnless { it.isBlank() }
        val normalizedHref = when {
            href.isNullOrBlank() -> ""
            href.startsWith("http://") || href.startsWith("https://") -> href.removePrefix(baseUrl)
            else -> href
        }

        if (normalizedHref.isNotBlank()) {
            setUrlWithoutDomain(normalizedHref)
        }

        title = element.selectFirst("h2.title-name, h2.text-[1.08rem]")?.text()?.trim()
            ?: element.selectFirst(".title, h3, h4, .card-title")?.text()?.trim()
            ?: element.text().trim().takeIf { it.isNotBlank() }.orEmpty()

        thumbnail_url = element.selectFirst("img")?.let { image ->
            image.absUrl("src").takeIf { it.isNotBlank() }
                ?: image.attr("src").takeIf { it.isNotBlank() }
                ?: image.attr("data-src").takeIf { it.isNotBlank() }
        }
    }

    override fun popularAnimeNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesNextPageSelector(): String? = throw UnsupportedOperationException("Not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = buildSearchUrl(page, query, filters)
        return GET(url, headers)
    }

    fun buildSearchUrl(page: Int, query: String, filters: AnimeFilterList): String {
        val queryParams = mutableListOf<Pair<String, String>>()

        if (query.isNotBlank()) {
            queryParams += "q" to query
        }

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> filter.getQueryValue()?.let { value ->
                    queryParams += "status[0]" to value
                }
                is GenresFilter -> filter.getQueryValues().forEach { queryParam ->
                    queryParams += queryParam
                }
                else -> Unit
            }
        }

        val path = if (query.isBlank()) {
            "titles/list"
        } else {
            "search"
        }

        val filteredQueryParams = queryParams.toMutableList().apply {
            add("page" to page.toString())
        }

        return buildUrl(baseUrl, path, filteredQueryParams)
    }

    private fun buildUrl(baseUrl: String, path: String, params: List<Pair<String, String>>): String {
        val query = params.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }
        return if (query.isBlank()) {
            "$baseUrl/$path"
        } else {
            "$baseUrl/$path?$query"
        }
    }

    // Select the title-card container rather than the inner anchor to be robust
    // in case the anchor structure varies between pages (search vs popular).
    override fun searchAnimeSelector(): String = "div.title-card"

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select(searchAnimeSelector()).mapNotNull { element ->
            runCatching { searchAnimeFromElement(element) }
                .getOrNull()
                ?.takeIf { it.url?.isNotBlank() == true }
        }
        val hasNextPage = searchAnimeNextPageSelector()?.let { selector ->
            document.selectFirst(selector) != null
        } ?: false
        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        // Title may live in several possible tags inside the card — prefer the explicit title elements.
        title = element.selectFirst("h2.title-name, h2.text-[1.08rem], .title, h3, h4, .card-title")
            ?.text()?.trim()
            ?: element.text().trim().takeIf { it.isNotBlank() }.orEmpty()

        // Prefer an explicit anchor inside the card for the URL (accept absolute or relative)
        val anchor = element.selectFirst("a[href*='/titles/']")
            ?: return SAnime.create()

        val href = anchor.absUrl("href").takeUnless { it.isBlank() }
            ?: return SAnime.create()

        val normalizedHref = when {
            href.startsWith("http://") || href.startsWith("https://") -> href.removePrefix(baseUrl)
            else -> href
        }

        setUrlWithoutDomain(normalizedHref)

        // Thumbnail image may be in an img inside the card or inside the anchor
        val image = element.selectFirst("img") ?: anchor.selectFirst("img")
        thumbnail_url = image?.let { img ->
            img.absUrl("src").takeIf { it.isNotBlank() }
                ?: img.attr("src").takeIf { it.isNotBlank() }
                ?: img.attr("data-src").takeIf { it.isNotBlank() }
        }
    }

    override fun searchAnimeNextPageSelector(): String? = "a[rel=next], button[rel=next]"

    override fun animeDetailsRequest(anime: SAnime): Request {
        // anime.url is stored without domain (e.g. "/titles/naruto")
        val animeUrl = anime.url.orEmpty()
        val url = if (animeUrl.startsWith("http://") || animeUrl.startsWith("https://")) animeUrl else "$baseUrl$animeUrl"
        return GET(url, headers)
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val infoContainer = document.selectFirst("div[class='w-[500px] flex-grow flex flex-col gap-4']")
            ?: return@apply

        title = infoContainer.selectFirst("div.flex.flex-wrap.gap-2.items-baseline > h1.text-2xl.font-bold.uppercase.inline")
            ?.text()?.trim().orEmpty()

        genre = infoContainer.select("div[class='flex flex-wrap gap-2 lg:gap-4 text-sm sm:text-[0.94rem] -mt-2 mb-4'] > a[href*='/genre/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }.joinToString(", ")

        description = infoContainer.select("div[x-data]").firstOrNull()?.let { xData ->
            xData.selectFirst("div[x-show=\"! summary\"]")
                ?: xData.selectFirst("div[x-show=\"summary\"]")
        }?.children()
            ?.filter { it.tagName().equals("p", ignoreCase = true) }
            ?.map { it.text().trim() }
            ?.joinToString(separator = "\n\n")
            .orEmpty()

        val infoTable = infoContainer.selectFirst("table.leading-loose.mx-auto.w-full")
        if (infoTable != null) {
            for (row in infoTable.select("tr")) {
                val tds = row.select("td")
                if (tds.size < 2) continue
                val label = tds[0].text().trim().removeSuffix(":").trim()
                val value = tds[1].text().trim()
                when {
                    label.contains("الحالة") -> {
                        status = when {
                            value.contains("منتهي") || value.contains("مكتمل") -> SAnime.COMPLETED
                            value.contains("مستمر") || value.contains("جاري") -> SAnime.ONGOING
                            else -> SAnime.UNKNOWN
                        }
                    }
                    label.contains("المؤلف") -> author = value
                    label.contains("الاستديو") || label.contains("الاستوديو") -> artist = value
                }
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val html = response.body.string()
        val rebuiltBody = html.toResponseBody(response.body.contentType())
        val rebuiltResponse = response.newBuilder().body(rebuiltBody).build()
        return super.episodeListParse(rebuiltResponse)
    }

    override fun episodeListSelector(): String = "div.flex.flex-grow.flex-col > a[href*='/episode/']"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val href = element.absUrl("href").takeUnless { it.isBlank() }
            ?: element.attr("href").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Episode anchor missing href")
        val normalizedHref = when {
            href.startsWith("http://") || href.startsWith("https://") -> href.removePrefix(baseUrl)
            else -> href
        }
        setUrlWithoutDomain(normalizedHref)

        val episodeNumber = element.selectFirst("div.video-data > div > span")?.text()?.trim()
        val episodeDesc = element.selectFirst("div.video-data > p")?.text()?.trim()
        name = listOfNotNull(episodeNumber, episodeDesc).joinToString(" - ")
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException("Not used")

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException("Not used")

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val playerUrl = extractVid3rbPlayerUrl(document)

        val playerHeaders = headers.newBuilder()
            .set("referer", response.request.url.toString())
            .build()

        val playerDocument = client.newCall(GET(playerUrl, playerHeaders)).execute().useAsJsoup()
        val videos = parseVid3rbPlayerSources(playerDocument, playerHeaders)

        if (videos.isEmpty()) {
            throw IllegalStateException("No playable video sources found in Vid3rb player page")
        }

        return videos
    }

    private fun resolveUrl(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> "$baseUrl$url"
        else -> "$baseUrl/$url"
    }

    private fun extractVid3rbPlayerUrl(document: Document): String {
        val wireSnapshotElement = document.getElementsByAttribute("wire:snapshot")
            .firstOrNull { it.attr("wire:snapshot").contains("\"video_url\"") }

        if (wireSnapshotElement != null) {
            val snapshot = wireSnapshotElement.attr("wire:snapshot")
            val extracted = runCatching {
                val parsed = Json.parseToJsonElement(snapshot).jsonObject
                parsed["data"]?.jsonObject?.get("video_url")?.jsonPrimitive?.content
            }.getOrNull()

            val videoUrl = extracted ?: Regex("\"video_url\":\"([^\"]+)\"").find(snapshot)?.groupValues?.get(1)
            if (!videoUrl.isNullOrBlank()) {
                return resolveUrl(videoUrl.replace("\\/", "/"))
            }
        }

        val iframePlayerUrl = document.selectFirst("iframe[src*='vid3rb.com']")?.absUrl("src")
            ?: document.selectFirst("iframe[src*='/embed/']")?.absUrl("src")
        if (!iframePlayerUrl.isNullOrBlank()) {
            return iframePlayerUrl
        }

        val scriptData = document.select("script").asSequence()
            .map { it.data() }
            .firstOrNull { it.contains("video.vid3rb.com/player") || it.contains("video.vid3rb.com\\/player") }
            ?: throw IllegalStateException("Vid3rb player URL not found in episode page scripts")

        val escapedMatch = ESCAPED_VID3RB_PLAYER_REGEX.find(scriptData)?.value
        val rawMatch = UNESCAPED_VID3RB_PLAYER_REGEX.find(scriptData)?.value
        val matchedUrl = escapedMatch ?: rawMatch
            ?: throw IllegalStateException("Vid3rb player URL not found in episode page script content")

        return resolveUrl(matchedUrl.replace("\\/", "/"))
    }

    private fun parseVid3rbPlayerSources(document: Document, headers: Headers): List<Video> {
        val scriptData = document.select("script").asSequence()
            .map { it.data() }
            .firstOrNull { it.contains("var video_sources") }
            ?: throw IllegalStateException("Vid3rb player script block with video_sources not found")

        val arrayBody = scriptData
            .substringAfterLast("var video_sources = [", missingDelimiterValue = "")
            .substringBefore("];", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Vid3rb player video_sources array could not be extracted")

        val sourceJson = "[$arrayBody]"
        val sources = try {
            Json.decodeFromString<List<Vid3rbSource>>(sourceJson)
        } catch (e: Exception) {
            throw IllegalStateException("Failed parsing Vid3rb video sources", e)
        }

        return sources
            .filter { it.src.isNotBlank() }
            .filterNot { it.premium == true }
            .ifEmpty { sources.filter { it.src.isNotBlank() } }
            .sortedWith(
                compareByDescending<Vid3rbSource> { it.res?.toIntOrNull() ?: 0 }
                    .thenByDescending { it.label ?: "" },
            )
            .map { source ->
                val quality = source.label?.takeIf { it.isNotBlank() }
                    ?: source.res?.let { "${it}p" }
                    ?: "Vid3rb"

                Video(
                    url = source.src,
                    quality = quality,
                    videoUrl = source.src,
                    headers = headers,
                )
            }
    }

    @Serializable
    private data class Vid3rbSource(
        val src: String = "",
        val type: String? = null,
        val label: String? = null,
        val res: String? = null,
        val premium: Boolean? = null,
    )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        StatusFilter(),
        GenresFilter(),
    )

    class StatusFilter : AnimeFilter.Select<String>("الحالة", STATUS_ENTRIES, 0) {
        fun getQueryValue(): String? = STATUS_VALUES.getOrNull(state)?.takeIf { it.isNotBlank() }

        companion object {
            private val STATUS_ENTRIES = arrayOf("الكل", "قيد البث", "منتهي", "قادم")
            private val STATUS_VALUES = arrayOf("", "running", "finished", "upcomming")
        }
    }

    class GenreOption(displayName: String, val value: String) : AnimeFilter.CheckBox(displayName, false)

    class GenresFilter : AnimeFilter.Group<GenreOption>("التصنيفات", GENRES.map { (name, value) -> GenreOption(name, value) }) {
        fun getQueryValues(): List<Pair<String, String>> = state.filter { it.state }
            .mapIndexed { index, option -> "genres[$index]" to option.value }

        companion object {
            private val GENRES = listOf(
                "أكشن" to "action",
                "كوميدي" to "comedy",
                "خيال" to "fantasy",
                "مغامرة" to "adventure",
                "دراما" to "drama",
                "شونين" to "shounen",
                "رومانسي" to "romance",
                "مدرسي" to "school",
                "خيال علمي" to "sci-fi",
                "خارق للطبيعة" to "supernatural",
                "سينين" to "seinen",
                "غموض" to "mystery",
                "إيتشي" to "ecchi",
                "بطولة راشدين" to "adult-cast",
                "تاريخي" to "historical",
                "الحياة اليومية" to "slice-of-life",
                "ميكا" to "mecha",
                "قوى خارقة" to "super-power",
                "حريم" to "harem",
                "عسكري" to "military",
                "رياضي" to "sports",
                "إيسيكاي" to "isekai",
                "تشويق" to "suspense",
                "شوچو" to "shoujo",
                "أساطير" to "mythology",
                "نفسي" to "psychological",
                "رعب" to "horror",
                "موسيقى" to "music",
                "دموي" to "gore",
                "ساخر" to "parody",
                "قتالي" to "martial-arts",
                "بوليسي" to "detective",
                "فضاء" to "space",
                "كيوت" to "cgdct",
                "حائز على جوائز" to "award-winning",
                "رياضات جماعية" to "team-sports",
                "كوميديا حركية" to "gag-humor",
                "للأطفال" to "kids",
                "إياشيكي" to "iyashikei",
                "خيال حضري" to "urban-fantasy",
                "عمل" to "workplace",
                "فتاة ساحرة" to "mahou-shoujo",
                "تناسخ و إعادة إحياء" to "reincarnation",
                "مصاصي دماء" to "vampire",
                "أنثروبولوجي" to "anthropomorphic",
                "ساموراي" to "samurai",
                "سفر عبر الزمن" to "time-travel",
                "چوسي" to "josei",
                "استراتيجي" to "strategy-game",
                "حب متعدد الأطراف" to "love-polygon",
                "ثقافة الأوتاكو" to "otaku-culture",
                "أيدول إناث" to "idols-female",
                "جريمة منظمة" to "organized-crime",
                "طعام" to "gourmet",
                "ألعاب فيديو" to "video-game",
                "نجاة" to "survival",
                "فنون استعراضية" to "performing-arts",
                "سباق" to "racing",
                "حب فتيات" to "girls-love",
                "ابتكاري" to "avant-garde",
                "عكس حريم" to "reverse-harem",
                "رياضات قتالية" to "combat-sports",
                "رعاية أطفال" to "childcare",
                "فنون بصرية" to "visual-arts",
                "حالة حب" to "love-status-quo",
                "ألعاب عالية المخاطر" to "high-stakes-game",
                "جانحون" to "delinquents",
                "أيدول ذكور" to "idols-male",
                "حيوانات أليفة" to "pets",
                "تنكر في ملابس الجنس الآخر" to "crossdressing",
                "طبي" to "medical",
                "حب فتيان" to "boys-love",
                "تبديل جنسي سحري" to "magical-sex-shift",
                "صناعة الترفيه" to "showbiz",
                "شريرة" to "villainess",
                "ايروتيكا" to "erotica",
                "تعليمية" to "educational",
            )
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).apply()
                true
            }
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy<Video> {
                if (it.quality.contains(preferredQuality, ignoreCase = true)) 0 else 1
            }.thenByDescending { it.quality.filter { char -> char.isDigit() }.toIntOrNull() ?: 0 },
        )
    }

    companion object {

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p")

        private val ESCAPED_VID3RB_PLAYER_REGEX = """https?:\/\/video\.vid3rb\.com\/player\/[^\"]+""".toRegex()
        private val UNESCAPED_VID3RB_PLAYER_REGEX = """https?://video\.vid3rb\.com/player/[^\"]+""".toRegex()
    }
}
