package eu.kanade.tachiyomi.animeextension.en.kimcartoon

import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class KimCartoon : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "KimCartoon"

    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
    }

    override val lang = "en"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
        )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/CartoonList/MostPopular?page=$page", headers)

    override fun popularAnimeSelector(): String = "div.list-cartoon > div.item"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        title = element.selectFirst("a.thumb h2.title")?.text() ?: ""
        setUrlWithoutDomain(element.selectFirst("a.thumb")?.attr("href") ?: "")
        thumbnail_url = element.selectFirst("a.thumb img")?.attr("src")
    }

    override fun popularAnimeNextPageSelector(): String? = "ul.pager a:contains(>>)"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/CartoonList/LatestUpdate?page=$page", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/Search/?s=$query&page=$page", headers)

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = popularAnimeNextPageSelector()

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1")?.text() ?: ""
        description = document.selectFirst("div.desc p")?.text() ?: ""
        thumbnail_url = document.selectFirst("div.thumb img")?.attr("src")
        genre = document.select("a[href^='/Genre/']").joinToString(", ") { it.text() }
        status = when {
            document.select("div.status:contains(Ongoing)").isNotEmpty() -> SAnime.ONGOING
            document.select("div.status:contains(Completed)").isNotEmpty() -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("div.full.item_ep").map { element ->
            SEpisode.create().apply {
                name = element.selectFirst("h3 a")?.text() ?: ""
                setUrlWithoutDomain(element.selectFirst("h3 a")?.attr("href") ?: "")
                date_upload = element.selectFirst("div")?.text()?.let { parseDate(it) } ?: 0L
                episode_number = Regex("""(\d+(\.\d+)?)""").find(name)?.value?.toFloatOrNull() ?: 0f
            }
        }.reversed()
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = "$baseUrl${episode.url}"
        val document = client.newCall(GET(episodeUrl, headers)).await().asJsoup()

        val episodeId = episodeUrl.toHttpUrl().queryParameter("id")
            ?: throw Exception("No episode ID found")

        val ctk = document.selectFirst("script:containsData(ctk)")
            ?.data()
            ?.substringAfter("var ctk = '")
            ?.substringBefore("';")
            ?: throw Exception("No CTK found")

        val serverOptions = document.select("select#selectServer > option")

        val serverList = serverOptions.mapNotNull { option ->
            val serverUrl = baseUrl + option.attr("value")
            val serverName = serverUrl.toHttpUrl().queryParameter("s") ?: return@mapNotNull null
            Server(option.text(), serverName, serverUrl)
        }

        return serverList.parallelCatchingFlatMap { server ->
            val iframeJson = client.newCall(
                POST(
                    "$baseUrl/ajax/anime/load_episodes_v2?s=${server.name}",
                    body = "episode_id=$episodeId&ctk=$ctk".toRequestBody(FORM_URLENCODED),
                    headers = Headers.headersOf(
                        "Content-Type", "application/x-www-form-urlencoded",
                        "Referer", server.url,
                        "X-Requested-With", "XMLHttpRequest",
                    ),
                ),
            ).await().body.string()

            val iframeHtml = json.decodeFromString<IframeResponse>(iframeJson).value
            val iframeSrc = Jsoup.parse(iframeHtml).selectFirst("iframe")?.attr("src") ?: return@parallelCatchingFlatMap emptyList()
            val videoUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc

            extractVideos(videoUrl, server.label)
        }
    }

    private fun extractVideos(url: String, label: String): List<Video> = when {
        url.contains("streamtape") -> {
            StreamTapeExtractor(client).videosFromUrl(url, label)
        }
        url.contains("dood") || url.contains("dooood") -> {
            DoodExtractor(client).videosFromUrl(url, label)
        }
        url.contains("mixdrop") -> {
            MixDropExtractor(client).videoFromUrl(url, prefix = label)
        }
        else -> {
            try {
                val iframeDoc = client.newCall(GET(url, headers)).execute().asJsoup()
                val videoSrc = iframeDoc.selectFirst("video source[src], video[src]")?.attr("src")
                if (videoSrc != null) {
                    listOf(Video(videoSrc, "$label - Direct", videoSrc))
                } else {
                    listOf(Video(url, "$label - Iframe", url))
                }
            } catch (_: Exception) {
                listOf(Video(url, "$label - Iframe", url))
            }
        }
    }

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> = this

    private fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr)?.time }
        .getOrNull() ?: 0L

    @Serializable
    data class IframeResponse(val value: String)

    data class Server(
        val label: String,
        val name: String,
        val url: String,
    )

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MM/dd/yyyy", Locale.ENGLISH)
        }

        private val FORM_URLENCODED = "application/x-www-form-urlencoded".toMediaType()

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://kimcartoon.si"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("kimcartoon.si", "kimcartoon.to", "kimcartoon.se", "kimcartoon.bz")
            entryValues = arrayOf(
                "https://kimcartoon.si",
                "https://kimcartoon.to",
                "https://kimcartoon.se",
                "https://kimcartoon.bz",
            )
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                preferences.edit().putString(key, entryValues[index].toString()).apply()
            }
        }.also(screen::addPreference)
    }
}
