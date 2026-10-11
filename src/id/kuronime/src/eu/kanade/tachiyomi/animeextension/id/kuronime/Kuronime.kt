package eu.kanade.tachiyomi.animeextension.id.kuronime

import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.pixeldrainextractor.PixelDrainExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
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
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParse
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

    private val preferences by getPreferencesLazy {
        val storedHosts = getStringSet(PREF_HOSTER_KEY, null)
        if (storedHosts != null) {
            val migrated = storedHosts - DEPRECATED_HOSTS
            when {
                migrated.isEmpty() && storedHosts.isNotEmpty() ->
                    edit().putStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT).apply()

                migrated != storedHosts ->
                    edit().putStringSet(PREF_HOSTER_KEY, migrated).apply()
            }
        }
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val pixelDrainExtractor by lazy { PixelDrainExtractor() }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
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
        val infoMap = parseInfoMap(document)

        anime.title = (document.selectFirst("h1.entry-title, h1")?.text() ?: infoMap["judul"])!!

        val genres = document.select("div.infodetail li:contains(Genre) a")
            .map { it.text() }
            .filter { it.isNotEmpty() }
        anime.genre = if (genres.isNotEmpty()) genres.joinToString() else infoMap["genre"]

        anime.status = parseStatus(infoMap["status"] ?: "")

        val studio = document.select("div.infodetail li:contains(Studio) a")
            .map { it.text() }
            .filter { it.isNotEmpty() }
            .joinToString()
        anime.artist = studio.ifEmpty { infoMap["studio"] }

        val conx = document.selectFirst("div.main-info div.con div.r div.conx, div.conx")
        anime.description = conx?.let { element ->
            element.select("p").map { it.text() }.filter { it.isNotEmpty() }.joinToString("\n\n")
                .ifEmpty { element.text() }
        }

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

        val infoMap = parseInfoMap(document)
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
        episode.name = name

        val epMatch = EPISODE_REGEX.find(name) ?: NUMBER_REGEX.find(name)
        episode.episode_number = epMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 1F

        return episode
    }

    // Class-level per CONTRIBUTING.md#date-parsing - avoid reconstructing per episode.
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
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!

        val videoList = mutableListOf<Video>()

        // 1. Try reverse-engineered animeku sources API
        val encryptedId = document.select("script")
            .firstNotNullOfOrNull { SCRIPT_PAYLOAD_REGEX.find(it.data())?.groupValues?.get(1) }
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
            }.onFailure {
                Log.w(TAG, "Animeku sources API failed, falling back to mirrors", it)
            }
        }

        // 2. Fallback to old mirror select if present
        if (videoList.isEmpty()) {
            val fallbackVideos = document.select("select.mirror > option[value]").parallelCatchingFlatMapBlocking { opt ->
                val decoded = if (opt.attr("value").isEmpty()) {
                    document.selectFirst("iframe")?.attr("data-src") ?: ""
                } else {
                    Jsoup.parseBodyFragment(
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
    ): List<Video> {
        val serverKey = server.lowercase(Locale.ROOT)
        val urlKey = url.lowercase(Locale.ROOT)
        fun matches(vararg keys: String) = keys.any { it in serverKey || it in urlKey }

        return when {
            matches("mp4upload") && hosterSelection.contains("mp4upload") -> {
                mp4uploadExtractor.videosFromUrl(url, headers, suffix = " - $quality")
            }

            matches("pixeldrain") && hosterSelection.contains("pixeldrain") -> {
                pixelDrainExtractor.videosFromUrl(url.substringBefore("?"), prefix = "$quality - ")
            }

            // StreamWish and FileLions share the same player implementation.
            matches("streamwish", "filelions") && hosterSelection.contains("vidhide") -> {
                streamWishExtractor.videosFromUrl(url) { q -> "VidHide/StreamWish - $quality ($q)" }
            }

            matches("vidhide") && hosterSelection.contains("vidhide") -> {
                vidHideExtractor.videosFromUrl(url) { q -> "VidHide - $quality ($q)" }
            }

            matches("dood", "d0000d", "do7go") && hosterSelection.contains("doodstream") -> {
                doodExtractor.videosFromUrl(url, quality)
            }

            matches("yourupload") && hosterSelection.contains("yourupload") -> {
                val yourUploadHeaders = headers.newBuilder().removeAll("Referer").build()
                yourUploadExtractor.videoFromUrl(url, yourUploadHeaders, name = "YourUpload", prefix = "$quality - ")
            }

            else -> emptyList()
        }
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
                    val idx = SERVER_ORDER.indexOfFirst { video.videoTitle.contains(it, ignoreCase = true) }
                    if (idx == -1) SERVER_ORDER.size else idx
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
        anime.title = titleElement?.text() ?: linkElement.attr("title").trim()
        return anime
    }

    private fun parseInfoMap(document: Document): Map<String, String> = document.select("div.infodetail ul li").associate { li ->
        val text = li.text()
        text.substringBefore(":").trim().lowercase(Locale.ROOT) to text.substringAfter(":").trim()
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = PREF_SERVER_TITLE,
            summary = "%s",
            entries = SERVER_ORDER,
            entryValues = SERVER_ORDER,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = PREF_QUALITY_TITLE,
            summary = "%s",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
        )

        screen.addSetPreference(
            key = PREF_HOSTER_KEY,
            default = PREF_HOSTER_DEFAULT,
            title = PREF_HOSTER_TITLE,
            summary = "",
            entries = PREF_HOSTER_ENTRIES.toList(),
            entryValues = PREF_HOSTER_VALUES.toList(),
        )
    }

    companion object {
        private const val TAG = "Kuronime"
        private const val SOURCES_API_URL = "https://animeku.org/api/v9/sources"
        private const val DECRYPTION_KEY = "3&!Z0M,VIZ;dZW=="

        private val SCRIPT_PAYLOAD_REGEX = Regex("""var\s+_0x[a-f0-9]+\s*=\s*["']([A-Za-z0-9+/=]{20,})["']""")
        private val EPISODE_REGEX = Regex("""(?i)(?:episode|eps\.?)\s*(\d+(?:\.\d+)?)""")
        private val NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")

        private val SERVER_ORDER = listOf("PixelDrain", "Mp4Upload", "VidHide", "YourUpload", "DoodStream")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred server"
        private const val PREF_SERVER_DEFAULT = "PixelDrain"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private const val PREF_HOSTER_TITLE = "Enable/Disable Hosts"
        private val PREF_HOSTER_ENTRIES = arrayOf("PixelDrain", "Mp4Upload", "VidHide/FileLions/StreamWish", "YourUpload", "DoodStream")
        private val PREF_HOSTER_VALUES = arrayOf("pixeldrain", "mp4upload", "vidhide", "yourupload", "doodstream")
        private val PREF_HOSTER_DEFAULT = PREF_HOSTER_VALUES.toSet()
        private val DEPRECATED_HOSTS = setOf("animeku", "streamlare", "hxfile", "linkbox")
    }
}
