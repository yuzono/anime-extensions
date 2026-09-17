package eu.kanade.tachiyomi.animeextension.id.otakudesu

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.bloggerextractor.BloggerExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMapNotNullBlocking
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class OtakuDesu :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "OtakuDesu"

    override val baseUrl = "https://otakudesu.blog"

    override val lang = "id"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val ajaxHeaders by lazy {
        headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    private val preferences by getPreferencesLazy()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val info = document.selectFirst("div.infozingle")!!
        title = info.getInfo("Judul") ?: ""
        genre = info.getInfo("Genre")
        status = parseStatus(info.getInfo("Status"))
        artist = info.getInfo("Studio")
        author = info.getInfo("Produser")

        description = buildString {
            info.getInfo("Japanese", false)?.also { append("$it\n") }
            info.getInfo("Skor", false)?.also { append("$it\n") }
            info.getInfo("Total Episode", false)?.also { append("$it\n") }
            append("\n\nSynopsis:\n")
            document.select("div.sinopc > p").eachText().forEach { append("$it\n\n") }
        }
    }

    private fun parseStatus(statusString: String?): Int = when (statusString) {
        "Ongoing" -> SAnime.ONGOING
        "Completed" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    private val nameRegex by lazy { ".+?(?=Episode)|\\sSubtitle.+".toRegex() }
    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val link = element.selectFirst("span > a")!!
        val text = link.text()
        episode_number = text.substringAfter("Episode ")
            .substringBefore(" ")
            .toFloatOrNull() ?: 1F
        setUrlWithoutDomain(link.attr("href"))
        name = text.replace(nameRegex, "")
        date_upload = element.selectFirst("span.zeebr")?.text().let(DATE_FORMATTER::tryParse)
    }

    override fun episodeListSelector() = "div.episodelist ul li:has(a[href*=/episode/])"

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")!!.attr("src")
        title = element.selectFirst("h2")!!.text()
    }

    override fun latestUpdatesNextPageSelector() = "a.next.page-numbers"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/ongoing-anime/page/$page")

    override fun latestUpdatesSelector() = "div.detpost div.thumb > a"

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/complete-anime/page/$page")
    override fun popularAnimeSelector() = latestUpdatesSelector()

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    private fun searchAnimeFromElement(element: Element, ui: String): SAnime = SAnime.create().apply {
        when (ui) {
            "search" -> {
                val link = element.selectFirst("h2 > a")!!
                setUrlWithoutDomain(link.attr("href"))
                title = link.text().replace(" Subtitle Indonesia", "")
                thumbnail_url = element.selectFirst("img")!!.attr("src")
            }

            else -> {
                val link = element.selectFirst(".col-anime-title > a")!!
                setUrlWithoutDomain(link.attr("href"))
                title = link.text()
                thumbnail_url = element.selectFirst(".col-anime-cover > img")!!.attr("src")
            }
        }
    }

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/?s=$query&post_type=anime")
            genreFilter.state != 0 -> GET("$baseUrl/genres/${genreFilter.toUriPart()}/page/$page")
            else -> GET("$baseUrl/complete-anime/page/$page")
        }
    }

    override fun searchAnimeSelector() = "#venkonten > div > div.venser > div > div > ul > li"
    private val genreSelector = ".col-anime"

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()

        val ui = when {
            document.selectFirst(genreSelector) == null -> "search"
            document.selectFirst(searchAnimeSelector()) == null -> "genres"
            else -> "unknown"
        }

        val animes = when (ui) {
            "genres" -> document.select(genreSelector).map { searchAnimeFromElement(it, ui) }
            "search" -> document.select(searchAnimeSelector()).map { searchAnimeFromElement(it, ui) }
            else -> document.select(latestUpdatesSelector()).map(::latestUpdatesFromElement)
        }

        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null

        return AnimesPage(animes, hasNextPage)
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = "div.mirrorstream ul li > a"

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()
        val script = doc.selectFirst("script:containsData(window.__x__nonce)")?.data()
            ?: doc.selectFirst("script:containsData(mirrorstream)")?.data()
            ?: return emptyList()

        val nonceAction = NONCE_ACTION_REGEX.find(script)?.groupValues?.get(1) ?: return emptyList()
        val action = ACTION_REGEX.find(script)?.groupValues?.get(1)
            ?: FALLBACK_ACTION_REGEX.find(script)?.groupValues?.get(1)
            ?: return emptyList()

        val nonce = runCatching { getNonce(nonceAction) }.getOrNull()?.takeIf(String::isNotBlank) ?: return emptyList()

        return doc.select(videoListSelector())
            .parallelMapNotNullBlocking {
                runCatching { getEmbedLinks(it, action, nonce) }.getOrNull()
            }
            .parallelCatchingFlatMapBlocking {
                getVideosFromEmbed(it.first, it.second)
            }
    }

    private suspend fun getEmbedLinks(element: Element, action: String, nonce: String): Pair<String, String>? {
        val rawContent = element.attr("data-content").takeIf(String::isNotBlank) ?: return null
        val decodedData = runCatching { rawContent.b64Decode() }.getOrNull() ?: return null

        val json = runCatching { JSONObject(decodedData) }.getOrNull() ?: return null
        val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
        val mirror = json.optString("i").takeIf(String::isNotBlank) ?: return null
        val quality = json.optString("q").takeIf(String::isNotBlank) ?: return null

        val form = FormBody.Builder().apply {
            add("id", id)
            add("i", mirror)
            add("q", quality)
            add("nonce", nonce)
            add("action", action)
        }.build()

        val responseString = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, form))
            .awaitSuccess()
            .bodyString()

        val b64Html = runCatching { JSONObject(responseString).getString("data") }.getOrNull()
            ?: DATA_REGEX.find(responseString)?.groupValues?.get(1)
            ?: return null

        val html = runCatching { b64Html.b64Decode() }.getOrNull() ?: return null
        val doc = Jsoup.parse(html)
        val rawUrl = doc.selectFirst("iframe")?.attr("src")?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("source")?.attr("src")?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("video")?.attr("src")?.takeIf(String::isNotBlank)
            ?: return null

        val url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl

        return Pair(quality, url)
    }

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val filelionsExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }

    private suspend fun getVideosFromEmbed(quality: String, link: String): List<Video> = when {
        "filedon" in link || "uservideo" in link || "userdrive" in link || "samevideo" in link -> {
            extractFiledon(quality, link)
        }

        "vidhide" in link -> {
            vidHideExtractor.videosFromUrl(link, videoNameGen = { "VidHide - $it ($quality)" })
        }

        "blogger" in link || "blogs" in link -> {
            bloggerExtractor.videosFromUrl(link, headers, suffix = quality)
        }

        "yourupload" in link || "yuplod" in link -> {
            val id = link.substringAfter("id=").substringBefore("&")
            val url = "https://yourupload.com/embed/$id"
            yourUploadExtractor.videoFromUrl(url, headers, "YourUpload - $quality")
        }

        "mp4upload" in link -> {
            mp4uploadExtractor.videosFromUrl(link, headers, suffix = " - $quality")
        }

        "streamwish" in link || "filelions" in link -> {
            filelionsExtractor.videosFromUrl(link, videoNameGen = { "StreamWish - $it" })
        }

        "desustream" in link || "desudrive" in link || "odstream" in link || "odcdn" in link || "otakuwatch" in link -> {
            extractDesuStream(quality, link)
        }

        isDirectMedia(link) -> {
            listOf(Video(link, "Direct - $quality", link, headers))
        }

        else -> {
            extractDesuStream(quality, link)
        }
    }

    private fun isDirectMedia(url: String): Boolean {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: url.substringBefore('?')
        return path.endsWith(".mp4", ignoreCase = true) || path.endsWith(".m3u8", ignoreCase = true)
    }

    private suspend fun extractFiledon(quality: String, link: String): List<Video> = try {
        val doc = client.newCall(GET(link, headers)).awaitSuccess().useAsJsoup()
        val dataPage = doc.selectFirst("div#app")?.attr("data-page") ?: return emptyList()
        val json = JSONObject(dataPage)
        val props = json.getJSONObject("props")
        val videoUrl = props.getString("url")
        listOf(Video(videoUrl, "Filedon - $quality", videoUrl, headers))
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun extractDesuStream(quality: String, link: String): List<Video> = try {
        val desuHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val doc = client.newCall(GET(link, desuHeaders)).awaitSuccess().useAsJsoup()

        val rawSource = doc.selectFirst("video source")?.attr("src")?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("video")?.attr("src")?.takeIf(String::isNotBlank)

        val sourceUrl = rawSource?.let {
            when {
                it.startsWith("//") -> "https:$it"
                it.startsWith("http") -> it
                else -> null
            }
        }

        if (sourceUrl != null) {
            val videoHeaders = headers.newBuilder().set("Referer", link).build()
            listOf(Video(sourceUrl, "DesuStream - $quality", sourceUrl, videoHeaders))
        } else {
            val script = doc.select("script").joinToString("\n") { it.data() }
            val videoUrl = DESU_FILE_REGEX.find(script)?.groupValues?.get(1)
                ?.let {
                    when {
                        it.startsWith("//") -> "https:$it"
                        it.startsWith("http") -> it
                        else -> null
                    }
                }

            if (videoUrl != null) {
                val videoHeaders = headers.newBuilder().set("Referer", link).build()
                listOf(Video(videoUrl, "DesuStream - $quality", videoUrl, videoHeaders))
            } else {
                emptyList()
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun getNonce(action: String): String {
        val form = FormBody.Builder().add("action", action).build()
        val responseString = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, form))
            .execute()
            .bodyString()
        return runCatching { JSONObject(responseString).getString("data") }.getOrNull()
            ?: DATA_REGEX.find(responseString)?.groupValues?.get(1)
            ?: ""
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter(),
    )

    private class GenreFilter :
        UriPartFilter(
            "Genres",
            arrayOf(
                Pair("<select>", ""),
                Pair("Action", "action"),
                Pair("Adventure", "adventure"),
                Pair("Comedy", "comedy"),
                Pair("Demons", "demons"),
                Pair("Drama", "drama"),
                Pair("Ecchi", "ecchi"),
                Pair("Fantasy", "fantasy"),
                Pair("Game", "game"),
                Pair("Harem", "harem"),
                Pair("Historical", "historical"),
                Pair("Horror", "horror"),
                Pair("Josei", "josei"),
                Pair("Magic", "magic"),
                Pair("Martial Arts", "martial-arts"),
                Pair("Mecha", "mecha"),
                Pair("Military", "military"),
                Pair("Music", "music"),
                Pair("Mystery", "mystery"),
                Pair("Psychological", "psychological"),
                Pair("Parody", "parody"),
                Pair("Police", "police"),
                Pair("Romance", "romance"),
                Pair("Samurai", "samurai"),
                Pair("School", "school"),
                Pair("Sci-Fi", "sci-fi"),
                Pair("Seinen", "seinen"),
                Pair("Shoujo", "shoujo"),
                Pair("Shoujo Ai", "shoujo-ai"),
                Pair("Shounen", "shounen"),
                Pair("Slice of Life", "slice-of-life"),
                Pair("Sports", "sports"),
                Pair("Space", "space"),
                Pair("Super Power", "super-power"),
                Pair("Supernatural", "supernatural"),
                Pair("Thriller", "thriller"),
                Pair("Vampire", "vampire"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }
        screen.addPreference(videoQualityPref)
    }

    // ============================= Utilities ==============================
    private fun Element.getInfo(info: String, cut: Boolean = true): String? = selectFirst("p > span:has(b:contains($info))")?.text()
        ?.let {
            when {
                cut -> it.substringAfter(":")
                else -> it
            }.trim()
        }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.videoTitle.contains(quality) },
        )
    }

    private fun String.b64Decode(): String = String(Base64.decode(this, Base64.DEFAULT))

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("d MMM,yyyy", Locale("id", "ID"))
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private val NONCE_ACTION_REGEX by lazy { """data\s*:\s*\{\s*action\s*:\s*"([a-f0-9]+)"""".toRegex() }
        private val ACTION_REGEX by lazy { """nonce\s*:\s*[^,]+,\s*action\s*:\s*"([a-f0-9]+)"""".toRegex() }
        private val FALLBACK_ACTION_REGEX by lazy { """action\s*:\s*"([a-f0-9]{32})"""".toRegex() }
        private val DATA_REGEX by lazy { """"data"\s*:\s*"([^"]+)"""".toRegex() }
        private val DESU_FILE_REGEX by lazy { """(?:file|videoURL)\s*[:=]\s*["']([^"']+)["']""".toRegex() }
    }
}
