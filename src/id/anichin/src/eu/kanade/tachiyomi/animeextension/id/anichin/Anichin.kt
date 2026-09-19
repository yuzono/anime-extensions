package eu.kanade.tachiyomi.animeextension.id.anichin

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.rumbleextractor.RumbleExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class Anichin :
    AnimeStream(
        "id",
        "Anichin",
        "https://anichin.moe",
    ) {

    // ============================== Preferences ==============================
    private val json by lazy { Json { ignoreUnknownKeys = true } }

    private val SharedPreferences.enabledHosters
        by preferences.delegate(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        screen.addSetPreference(
            key = PREF_HOSTER_KEY,
            title = "Pilih Host Video",
            summary = "Pilih host pemutar yang ingin dimuat. Menonaktifkan host yang lambat/terblokir akan mempercepat pemuatan video.",
            entries = INTERNAL_HOSTER_NAMES,
            entryValues = PREF_HOSTER_ENTRY_VALUES,
            default = PREF_HOSTER_DEFAULT,
        )
    }

    private companion object {
        private const val PREF_HOSTER_KEY = "anichin_hoster_selection"
        private val INTERNAL_HOSTER_NAMES = listOf(
            "TurboVIP",
            "OK.ru",
            "VidHide",
            "Dailymotion",
            "Doodstream",
            "StreamWish",
            "Rumble",
            "Mp4upload",
            "Streamtape",
            "YourUpload",
        )
        private val PREF_HOSTER_ENTRY_VALUES = listOf(
            "turbovip",
            "okru",
            "vidhide",
            "dailymotion",
            "dood",
            "streamwish",
            "rumble",
            "mp4upload",
            "streamtape",
            "yourupload",
        )

        // Default hanya mengaktifkan host yang cepat, stabil, dan bisa diputar langsung di Indonesia
        private val PREF_HOSTER_DEFAULT = setOf("turbovip", "okru", "vidhide", "dailymotion", "dood")
    }

    // =========================== Anime Details ============================
    override val animeAuthorText = "Subber"

    override fun animeDetailsParse(document: Document): SAnime = super.animeDetailsParse(document).apply {
        description = getAnimeDescription(document)
    }

    // ============================ Video Links =============================
    private val timeoutClient by lazy {
        client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(timeoutClient) }
    private val rumbleExtractor by lazy { RumbleExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(timeoutClient) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(timeoutClient) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(timeoutClient) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private data class MirrorItem(val name: String, val url: String)

    private fun isHostEnabled(name: String, url: String): Boolean {
        val n = name.lowercase()
        val u = url.lowercase()
        return when {
            u.contains("turbovidhls") || n.contains("turbovip") || (n.contains("new player") && !n.contains("ads")) -> {
                preferences.enabledHosters.contains("turbovip")
            }
            u.contains("ok.ru") || u.contains("racaty.my.id/empire/") || n.contains("ok.ru") || n.contains("okru") -> {
                preferences.enabledHosters.contains("okru")
            }
            u.contains("vidhide") || u.contains("morencius") || u.contains("minochinos") ||
                u.contains("vidhidepre") || u.contains("dhtpre") || n.contains("vidhide") -> {
                preferences.enabledHosters.contains("vidhide")
            }
            u.contains("dailymotion") || u.contains("anichin-player.web.id") || n.contains("dailymotion") -> {
                preferences.enabledHosters.contains("dailymotion")
            }
            u.contains("dood") || u.contains("playmogo") || n.contains("dood") -> {
                preferences.enabledHosters.contains("dood")
            }
            u.contains("streamwish") || u.contains("streamruby") || u.contains("ruby") || u.contains("wish") ||
                n.contains("streamwish") || n.contains("streamruby") -> {
                preferences.enabledHosters.contains("streamwish")
            }
            u.contains("rumble") || n.contains("rumble") -> {
                preferences.enabledHosters.contains("rumble")
            }
            u.contains("mp4upload") || n.contains("mp4") -> {
                preferences.enabledHosters.contains("mp4upload")
            }
            u.contains("streamtape") || n.contains("streamtape") -> {
                preferences.enabledHosters.contains("streamtape")
            }
            u.contains("yourupload") || n.contains("yourupload") -> {
                preferences.enabledHosters.contains("yourupload")
            }
            else -> false
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()
        val optionElements = doc.select(videoListSelector())

        val allItems = optionElements.mapNotNull { element ->
            val name = element.text()
            val encodedData = when (element.tagName()) {
                "option" -> element.attr("value")
                "a" -> element.attr("data-em")
                else -> ""
            }
            val url = runBlocking { getHosterUrl(encodedData) }
            if (url.isBlank()) null else MirrorItem(name, url)
        }

        val items = allItems.filter { isHostEnabled(it.name, it.url) }
        if (allItems.isNotEmpty() && items.isEmpty()) {
            return emptyList()
        }

        var videos = items.parallelCatchingFlatMapBlocking { item ->
            withTimeoutOrNull(5000L) {
                runCatching {
                    getVideoList(item.url, item.name)
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }

        if (videos.isEmpty()) {
            val defaultIframe = doc.selectFirst("#embed_holder iframe")?.safeUrl()
            if (!defaultIframe.isNullOrBlank() && isHostEnabled("Default", defaultIframe)) {
                videos = runBlocking {
                    withTimeoutOrNull(5000L) {
                        runCatching {
                            getVideoList(defaultIframe, "Default")
                        }.getOrDefault(emptyList())
                    }
                } ?: emptyList()
            }
        }

        return videos
    }

    override suspend fun getHosterUrl(encodedData: String): String = runCatching {
        val doc = if (encodedData.toHttpUrlOrNull() == null) {
            val decoded = runCatching {
                String(Base64.decode(encodedData, Base64.DEFAULT))
            }.getOrNull().orEmpty()
            Jsoup.parse(decoded)
        } else {
            timeoutClient.newCall(GET(encodedData, headers)).awaitSuccess().useAsJsoup()
        }
        doc.selectFirst(getEpisodeIframeSelector())?.safeUrl()
            ?: doc.selectFirst("meta[content~=.][itemprop=embedUrl]")?.safeUrl("content")
            ?: Regex("""(?:src|SRC)=["']([^"']+)["']""").find(doc.html())?.groupValues?.get(1)
            ?: ""
    }.getOrDefault("")

    private fun Element.safeUrl(attribute: String = "src"): String {
        val value = attr(attribute)
        return when {
            value.startsWith("http") -> value
            value.startsWith("//") -> "https:$value"
            else -> absUrl(attribute).ifEmpty { value }
        }
    }

    override suspend fun getVideoList(url: String, name: String): List<Video> {
        if (url.isBlank()) return emptyList()
        val hostLower = name.lowercase()
        val urlLower = url.lowercase()

        return when {
            urlLower.contains("turbovidhls") || hostLower.contains("turbovip") -> {
                extractTurboVip(url)
            }

            urlLower.contains("ok.ru") || urlLower.contains("racaty.my.id/empire/") ||
                hostLower.contains("ok.ru") || hostLower.contains("okru") -> {
                val okUrl = if (urlLower.contains("racaty.my.id/empire/")) {
                    val id = Regex("""/empire/(\d+)""").find(url)?.groupValues?.get(1)
                    if (id != null) "https://ok.ru/videoembed/$id" else url
                } else {
                    url
                }
                okruExtractor.videosFromUrl(okUrl, prefix = "OK.ru - ")
            }

            urlLower.contains("anichin-player.web.id") || urlLower.contains("dailymotion") || hostLower.contains("dailymotion") -> {
                val dmId = when {
                    urlLower.contains("anichin-player.web.id") -> url.toHttpUrlOrNull()?.queryParameter("video")
                    urlLower.contains("dailymotion.com") -> url.toHttpUrlOrNull()?.run { queryParameter("video") ?: pathSegments.lastOrNull() }
                    else -> null
                }
                if (!dmId.isNullOrBlank()) {
                    extractDailymotion(dmId)
                } else {
                    emptyList()
                }
            }

            hostLower.contains("vidhide") || urlLower.contains("vidhide") ||
                urlLower.contains("morencius") || urlLower.contains("minochinos") ||
                urlLower.contains("vidhidepre") || urlLower.contains("dhtpre") -> {
                vidHideExtractor.videosFromUrl(url) { "VidHide - $it" }
            }

            hostLower.contains("dood") || urlLower.contains("playmogo") || urlLower.contains("dood") -> {
                doodExtractor.videoFromUrl(url, prefix = "Doodstream")?.let(::listOf).orEmpty()
            }

            hostLower.contains("streamwish") || hostLower.contains("streamruby") ||
                urlLower.contains("ruby") || urlLower.contains("wish") -> {
                streamWishExtractor.videosFromUrl(url) { "StreamWish - $it" }
            }

            urlLower.contains("rumble.com") || hostLower.contains("rumble") -> {
                rumbleExtractor.videosFromUrl(url, prefix = "Rumble - ")
            }

            urlLower.contains("mp4upload") || hostLower.contains("mp4") -> {
                mp4uploadExtractor.videosFromUrl(url, headers, prefix = "Mp4upload - ")
            }

            urlLower.contains("streamtape") || hostLower.contains("streamtape") -> {
                streamTapeExtractor.videoFromUrl(url, quality = "Streamtape")?.let(::listOf).orEmpty()
            }

            urlLower.contains("yourupload") || hostLower.contains("yourupload") -> {
                yourUploadExtractor.videoFromUrl(url, headers = headers, name = "YourUpload")
            }

            else -> emptyList()
        }
    }

    private suspend fun extractTurboVip(url: String): List<Video> {
        return runCatching {
            val turboHeaders = headers.newBuilder()
                .set("Referer", baseUrl)
                .build()
            val doc = timeoutClient.newCall(GET(url, turboHeaders)).awaitSuccess().useAsJsoup()
            val m3u8Url = doc.selectFirst("div#video_player, div[data-hash]")?.attr("data-hash")
                ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(doc.html())?.value
                ?: return emptyList()

            playlistUtils.extractFromHls(
                m3u8Url,
                referer = url,
                videoNameGen = { "TurboVIP - $it" },
            )
        }.getOrDefault(emptyList())
    }

    private suspend fun extractDailymotion(videoId: String): List<Video> {
        return runCatching {
            val dmHeaders = headers.newBuilder()
                .set("Referer", "https://www.dailymotion.com/")
                .build()
            val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
            val html = timeoutClient.newCall(GET(embedUrl, dmHeaders)).awaitSuccess().use { it.body.string() }
            val v1st = Regex("""\"v1st\":\"([^\"]+)\"""").find(html)?.groupValues?.get(1).orEmpty()
            val ts = Regex("""\"ts\":(\d+)""").find(html)?.groupValues?.get(1).orEmpty()
            val jsonUrl = "https://www.dailymotion.com/player/metadata/video/$videoId?locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"
            val jsonStr = timeoutClient.newCall(GET(jsonUrl, dmHeaders)).awaitSuccess().use { it.body.string() }
            val jsonObj = json.parseToJsonElement(jsonStr).jsonObject
            val qualities = jsonObj["qualities"]?.jsonObject ?: return emptyList()
            val autoList = qualities["auto"]?.jsonArray ?: return emptyList()
            val m3u8Url = autoList.firstNotNullOfOrNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }
                ?: return emptyList()

            playlistUtils.extractFromHls(
                m3u8Url,
                referer = "https://www.dailymotion.com/",
                videoNameGen = { "Dailymotion - $it" },
            )
        }.getOrDefault(emptyList())
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.videoSortPref
        return sortedWith(
            compareBy(
                { it.videoTitle.contains(quality, true) },
                { Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }
}
