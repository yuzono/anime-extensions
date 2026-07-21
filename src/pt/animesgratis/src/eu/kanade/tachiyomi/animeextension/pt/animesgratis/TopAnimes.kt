package eu.kanade.tachiyomi.animeextension.pt.animesgratis

import android.util.Base64
import android.util.Log
import aniyomi.lib.bloggerextractor.BloggerExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.m3u8server.M3u8Integration
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesgratis.extractors.EmbedPlayerExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesgratis.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class TopAnimes :
    DooPlay(
        "pt-BR",
        "TopAnimes",
        "https://topanimes.net",
    ) {

    private val tag by lazy { javaClass.simpleName }

    override val id: Long = 2969482460524685571L

    override val dateFormatter by lazy {
        SimpleDateFormat("dd/MM/yy", Locale("pt", "BR"))
    }

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.items.featured article div.poster"
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes/", headers)

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodio"

    // =============================== Search ===============================
    override fun searchAnimeSelector() = "div.result-item article div.thumbnail > a"
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // =========================== Anime Details ============================
    override val additionalInfoSelector = "div.wp-content"

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")!!
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            sheader.selectFirst("div.poster img")!!.let {
                thumbnail_url = it.getImageUrl()
                title = it.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")!!.text()
                }
            }

            genre = sheader.select("div.data div.sgeneros > a")
                .eachText()
                .joinToString()

            doc.selectFirst("div#info")?.let { info ->
                description = buildString {
                    append(doc.getDescription())
                    additionalInfoItems.forEach {
                        info.getInfo(it)?.let(::append)
                    }
                }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        val seasonName = season.selectFirst("span.se-t")?.text()
        return season.select(episodeListSelector()).mapNotNull { element ->
            runCatching {
                if (seasonName.isNullOrBlank()) {
                    episodeFromElement(element)
                } else {
                    episodeFromElement(element, seasonName)
                }
            }.onFailure { e ->
                Log.e(tag, "Failed to parse episode element", e)
            }.getOrNull()
        }
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val href = element.selectFirst("a[href]")!!
        setUrlWithoutDomain(href.attr("href"))
        val episodeName = href.ownText().trim()
        episode_number = element.selectFirst("div.numerando")
            ?.text()
            ?.let(episodeNumberRegex::find)
            ?.groupValues
            ?.last()
            ?.toFloatOrNull()
            ?: episodeName.substringAfter(" ").toFloatOrNull()
            ?: 0F
        name = episodeName
        date_upload = element.selectFirst(episodeDateSelector)
            ?.text()
            ?.toDate() ?: 0L
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val players = document.select("ul#playeroptionsul li")
        val videos = players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
            .let(m3u8Integration::processVideoList)
        videos.firstOrNull()?.let {
            Log.d(tag, "Selected stream URL: ${it.videoUrl?.take(100)}")
        }
        return videos
    }

    override val prefQualityValues = arrayOf("360p", "480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    private val m3u8Integration by lazy { M3u8Integration(client) }
    private val embedPlayerExtractor by lazy { EmbedPlayerExtractor(client, headers) }
    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private suspend fun getPlayerVideos(player: Element): List<Video> {
        val name = player.selectFirst("span.title")!!.text()
        val lower = name.lowercase()
        val url = getPlayerUrl(player) ?: return emptyList()
        Log.d(tag, "Fetching videos from: $url")

        var videos = when {
            "ruplay" in lower ||
                "noa" in lower ||
                "mdplayer" in lower ||
                "/antivirus2/" in url ||
                "/antivirus3/" in url ||
                "alibabacdn.net" in url -> embedPlayerExtractor.videosFromUrl(url, name)
            "streamwish" in lower -> streamWishExtractor.videosFromUrl(url)
            "filemoon" in lower -> filemoonExtractor.videosFromUrl(url)
            "mixdrop" in lower -> mixDropExtractor.videoFromUrl(url)
            "streamtape" in lower -> streamTapeExtractor.videosFromUrl(url)
            "/player/" in url -> bloggerExtractor.videosFromUrl(url, headers)
            "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)
            else -> emptyList()
        }

        if (videos.isEmpty()) {
            Log.d(tag, "No videos found for: $url, trying universal extractor")
            videos = universalExtractor.videosFromUrl(url, headers, name)
        }

        if (videos.isEmpty()) {
            Log.d(tag, "No videos found for: $url")
        }

        return videos
    }

    private fun getPlayerUrl(player: Element): String? {
        val playerId = player.attr("data-nume")
        val iframe = player.root().selectFirst("div#source-player-$playerId iframe") ?: return null

        val sourceUrl = iframe.tryGetAttr("data-litespeed-src", "src")?.takeIf(String::isNotBlank)
            ?: return null

        if ("/off/" in sourceUrl) {
            Log.w(tag, "Player $playerId is offline: $sourceUrl")
            return null
        }

        return resolvePlayerSourceUrl(sourceUrl)
    }

    private fun resolvePlayerSourceUrl(sourceUrl: String): String? {
        val httpUrl = sourceUrl.toHttpUrlOrNull() ?: run {
            Log.w(tag, "Invalid player URL: $sourceUrl")
            return null
        }

        return when {
            "/aviso/" in sourceUrl -> httpUrl.queryParameter("url")

            httpUrl.queryParameter("auth") != null -> decodeAuthPlayerUrl(httpUrl.queryParameter("auth"))

            else -> sourceUrl
        }
    }

    private fun decodeAuthPlayerUrl(auth: String?): String? {
        if (auth.isNullOrBlank()) {
            Log.w(tag, "Missing auth query parameter")
            return null
        }

        return runCatching {
            val decoded = String(Base64.decode(auth, Base64.DEFAULT))
            val content = json.decodeFromString<JsonObject>(decoded)
            content["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: run {
                    Log.w(tag, "Auth payload missing url field")
                    null
                }
        }.onFailure { e ->
            Log.e(tag, "Failed to decode auth player URL", e)
        }.getOrNull()
    }

    // ============================== Filters ===============================
    override fun genresListRequest() = popularAnimeRequest(0)
    override fun genresListSelector() = "div.filter > div.select:first-child option:not([disabled])"

    override fun genresListParse(document: Document): Array<Pair<String, String>> {
        val items = document.select(genresListSelector()).map {
            val name = it.text()
            val value = it.attr("value").substringAfter("$baseUrl/")
            Pair(name, value)
        }.toTypedArray()

        return if (items.isEmpty()) {
            items
        } else {
            arrayOf(Pair(selectFilterText, "")) + items
        }
    }

    // ============================= Utilities ==============================
    override fun getRealAnimeDoc(document: Document): Document {
        if (!document.location().contains("/episodio/")) return document

        return document.selectFirst("div.pag_episodes div.item > a:has(i.fa-th)")?.let {
            client.newCall(GET(it.attr("href"), headers)).execute()
                .use { response -> response.useAsJsoup() }
        } ?: document
    }

    @Suppress("SameParameterValue")
    private fun Element.tryGetAttr(vararg attributeKeys: String): String? = attributeKeys.firstOrNull { hasAttr(it) }
        ?.let { attr(it) }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality, ignoreCase = true) }
                .thenByDescending {
                    REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    companion object {
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
    }
}
