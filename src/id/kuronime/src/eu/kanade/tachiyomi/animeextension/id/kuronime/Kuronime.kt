package eu.kanade.tachiyomi.animeextension.id.kuronime

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.pixeldrainextractor.PixelDrainExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.cryptoaes.CryptoAES
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Kuronime :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {
    override val baseUrl: String = "https://kuronime.sbs"
    override val lang: String = "id"
    override val name: String = "Kuronime"
    override val supportsLatest: Boolean = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val pixelDrainExtractor by lazy { PixelDrainExtractor() }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page/?order=popular", headers)

    override fun popularAnimeSelector(): String = "div.listupd article"

    override fun popularAnimeFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    override fun popularAnimeNextPageSelector(): String = "div.pagination a.next, a.next.page-numbers"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime/?page=$page&status=ongoing&sub=&order=update", headers)

    override fun latestUpdatesSelector(): String = "div.listupd article"

    override fun latestUpdatesFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    override fun latestUpdatesNextPageSelector(): String = "div.pagination a.next, a.next.page-numbers"

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = "div.listupd article"

    override fun searchAnimeFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    override fun searchAnimeNextPageSelector(): String = "div.pagination a.next, a.next.page-numbers"

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val infoMap = document.select("div.infodetail ul li").associate { li ->
            val text = li.text()
            text.substringBefore(":").trim().lowercase(Locale.ROOT) to text.substringAfter(":").trim()
        }

        anime.title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?: infoMap["judul"]
            ?: ""

        val genres = document.select("div.infodetail li:contains(Genre) a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        anime.genre = if (genres.isNotEmpty()) {
            genres.joinToString(", ")
        } else {
            infoMap["genre"]
        }

        anime.status = parseStatus(infoMap["status"] ?: "")

        val studio = document.select("div.infodetail li:contains(Studio) a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
        anime.artist = studio.ifEmpty { infoMap["studio"] }
        anime.author = null

        val conx = document.selectFirst("div.main-info div.con div.r div.conx, div.conx")
        val synopsis = conx?.select("p")?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.joinToString("\n\n")
            ?.ifEmpty { conx.text().trim() }
        anime.description = synopsis

        val thumbnailElement = document.selectFirst("div.main-info div.l img, div.thumb img")
        anime.thumbnail_url = thumbnailElement?.attr("src")?.takeIf { it.isNotBlank() }
            ?: thumbnailElement?.attr("data-src")

        return anime
    }

    private fun parseStatus(statusString: String): Int = when (statusString.lowercase(Locale.ROOT)) {
        "ongoing" -> SAnime.ONGOING
        "completed" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = "div.bixbox.bxcl ul li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = document.select(episodeListSelector()).map { episodeFromElement(it) }

        val infoMap = document.select("div.infodetail ul li").associate { li ->
            val text = li.text()
            text.substringBefore(":").trim().lowercase(Locale.ROOT) to text.substringAfter(":").trim()
        }

        val updatedDate = parseDate(infoMap["updated on"])
        val releaseDate = parseDate(infoMap["released on"])

        if (episodeList.isNotEmpty()) {
            if (updatedDate > 0L) {
                episodeList.first().date_upload = updatedDate
            }
            if (releaseDate > 0L && episodeList.size > 1) {
                episodeList.last().date_upload = releaseDate
            }
        }

        return episodeList
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val linkElement = element.selectFirst("span.lchx a, a")!!
        episode.setUrlWithoutDomain(linkElement.attr("href"))

        val name = element.selectFirst("span.lchx")?.text() ?: linkElement.text()
        episode.name = name.trim()

        val epMatch = EPISODE_REGEX.find(name)
            ?: Regex("""(\d+(?:\.\d+)?)""").find(name)
        episode.episode_number = epMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 1F

        return episode
    }

    private val dateFormatterId by lazy {
        SimpleDateFormat("MMMM d, yyyy", Locale("id", "ID"))
    }

    private val dateFormatterEn by lazy {
        SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return dateFormatterId.tryParse(dateStr).takeIf { it > 0L }
            ?: dateFormatterEn.tryParse(dateStr)
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val html = document.html()

        val storedHosts = preferences.getStringSet(PREF_HOSTER_KEY, null)
        val hosterSelection = if (storedHosts == null || storedHosts.any { it in DEPRECATED_HOSTS }) {
            preferences.edit().putStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT).apply()
            PREF_HOSTER_DEFAULT
        } else {
            storedHosts
        }

        val videoList = mutableListOf<Video>()

        // 1. Try reverse-engineered animeku sources API
        val encryptedId = SCRIPT_PAYLOAD_REGEX.find(html)?.groupValues?.get(1)
        if (encryptedId != null) {
            runCatching {
                val apiHeaders = headers.newBuilder()
                    .set("Referer", "$baseUrl/")
                    .build()
                val reqBody = SourceRequestDto(id = encryptedId).toJsonRequestBody()
                val apiRes = client.newCall(POST(SOURCES_API_URL, headers = apiHeaders, body = reqBody))
                    .execute()
                    .use { it.parseAs<SourceResponseDto>() }

                val mirrorJson = String(Base64.decode(apiRes.mirror, Base64.DEFAULT), Charsets.UTF_8)
                val cryptoDto = mirrorJson.parseAs<CryptoDto>()
                val decrypted = CryptoAES.decryptWithSalt(cryptoDto.ct, cryptoDto.s, DECRYPTION_KEY)

                if (decrypted.isNotBlank()) {
                    val embedDto = decrypted.parseAs<DecryptedEmbedDto>()
                    val embed = embedDto.embed ?: emptyMap()

                    val videos = embed.entries.parallelCatchingFlatMapBlocking { (q, servers) ->
                        val quality = q.removePrefix("v")
                        servers.entries.mapNotNull { (server, url) ->
                            if (url.isNullOrBlank()) null else server to url
                        }.parallelCatchingFlatMapBlocking { (server, url) ->
                            extractVideos(server, url, quality, hosterSelection)
                        }
                    }
                    videoList.addAll(videos)
                }
            }
        }

        // 2. Fallback to old mirror select if present
        if (videoList.isEmpty()) {
            val fallbackVideos = document.select("select.mirror > option[value]").parallelCatchingFlatMapBlocking { opt ->
                val decoded = if (opt.attr("value").isEmpty()) {
                    document.selectFirst("iframe")?.attr("data-src") ?: ""
                } else {
                    Jsoup.parse(
                        String(Base64.decode(opt.attr("value"), Base64.DEFAULT)),
                    ).select("iframe[data-src~=.]").attr("data-src")
                }

                if (decoded.isNotBlank()) {
                    extractVideos("fallback", decoded, opt.text(), hosterSelection)
                } else {
                    emptyList()
                }
            }
            videoList.addAll(fallbackVideos)
        }

        return videoList
    }

    private suspend fun extractVideos(
        server: String,
        url: String,
        quality: String,
        hosterSelection: Set<String>,
    ): List<Video> = when {
        ("mp4upload" in server || "mp4upload" in url) && hosterSelection.contains("mp4upload") -> {
            mp4uploadExtractor.videosFromUrl(url, headers, suffix = " - $quality")
        }
        ("pixeldrain" in server || "pixeldrain" in url) && hosterSelection.contains("pixeldrain") -> {
            val cleanUrl = url.substringBefore("?")
            pixelDrainExtractor.videosFromUrl(cleanUrl, prefix = "$quality - ")
        }
        ("vidhide" in server || "filelions" in server || "vidhide" in url || "filelions" in url || "streamwish" in url) &&
            hosterSelection.contains("vidhide") -> {
            vidHideExtractor.videosFromUrl(url) { q -> "VidHide - $quality ($q)" }
        }
        ("dood" in server || "d0000d" in url || "do7go" in url || "dood" in url) && hosterSelection.contains("doodstream") -> {
            doodExtractor.videosFromUrl(url, quality)
        }
        ("yourupload" in server || "yourupload" in url) && hosterSelection.contains("yourupload") -> {
            val yourUploadHeaders = headers.newBuilder().removeAll("Referer").build()
            yourUploadExtractor.videoFromUrl(url, yourUploadHeaders, name = "YourUpload", prefix = "$quality - ")
        }
        else -> emptyList()
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending { it.videoTitle.contains(server, ignoreCase = true) }
                .thenBy { video ->
                    val idx = SERVER_PRIORITY.indexOfFirst { video.videoTitle.contains(it, ignoreCase = true) }
                    if (idx == -1) SERVER_PRIORITY.size else idx
                },
        )
    }

    // ============================== Helpers ===============================

    private fun getAnimeFromAnimeElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a[itemprop=url], div.bsx > a, a")!!
        anime.setUrlWithoutDomain(linkElement.attr("href"))

        val thumbnailElement = element.selectFirst("div.limit img[itemprop=image], div.limit img:not([src*=controls-play]), img:not([src*=controls-play])")
            ?: element.selectFirst("img")
        anime.thumbnail_url = thumbnailElement?.attr("src")?.takeIf { it.isNotBlank() }
            ?: thumbnailElement?.attr("data-src")

        val titleElement = element.selectFirst("div.tt h2, div.tt h4, h2, h4")
        anime.title = titleElement?.text()?.trim() ?: linkElement.attr("title").trim()
        return anime
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val serverPref = ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val hostSelection = MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = PREF_HOSTER_TITLE
            entries = PREF_HOSTER_ENTRIES
            entryValues = PREF_HOSTER_VALUES
            setDefaultValue(PREF_HOSTER_DEFAULT)
        }
        screen.addPreference(serverPref)
        screen.addPreference(videoQualityPref)
        screen.addPreference(hostSelection)
    }

    companion object {
        private const val SOURCES_API_URL = "https://animeku.org/api/v9/sources"
        private const val DECRYPTION_KEY = "3&!Z0M,VIZ;dZW=="

        private val SCRIPT_PAYLOAD_REGEX = Regex("""var\s+_0x[a-f0-9]+\s*=\s*["']([A-Za-z0-9+/=]{20,})["']""")
        private val EPISODE_REGEX = Regex("""(?i)(?:episode|eps\.?)\s*(\d+(?:\.\d+)?)""")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred server"
        private const val PREF_SERVER_DEFAULT = "PixelDrain"
        private val PREF_SERVER_ENTRIES = arrayOf("PixelDrain", "Mp4Upload", "VidHide", "YourUpload", "DoodStream")
        private val PREF_SERVER_VALUES = PREF_SERVER_ENTRIES

        private val SERVER_PRIORITY = arrayOf("PixelDrain", "Mp4Upload", "VidHide", "YourUpload", "DoodStream")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360")

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private const val PREF_HOSTER_TITLE = "Enable/Disable Hosts"
        private val PREF_HOSTER_ENTRIES = arrayOf("PixelDrain", "Mp4Upload", "VidHide/FileLions", "YourUpload", "DoodStream")
        private val PREF_HOSTER_VALUES = arrayOf("pixeldrain", "mp4upload", "vidhide", "yourupload", "doodstream")
        private val PREF_HOSTER_DEFAULT = setOf("pixeldrain", "mp4upload", "vidhide", "yourupload", "doodstream")
        private val DEPRECATED_HOSTS = setOf("animeku", "streamlare", "hxfile", "linkbox")
    }
}

@Serializable
class SourceRequestDto(
    val id: String,
)

@Serializable
class SourceResponseDto(
    val mirror: String,
)

@Serializable
class CryptoDto(
    val ct: String,
    val s: String,
)

@Serializable
class DecryptedEmbedDto(
    val embed: Map<String, Map<String, String?>>? = null,
)
