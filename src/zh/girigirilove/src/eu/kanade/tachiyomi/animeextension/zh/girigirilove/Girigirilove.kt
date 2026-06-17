package eu.kanade.tachiyomi.animeextension.zh.girigirilove

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder

class Girigirilove :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Girigirilove"

    override val baseUrl = "https://ani.girigirilove.com"

    override val lang = "zh"

    override val supportsLatest = true

    private val json by injectLazy<Json>()
    private val preferences by getPreferencesLazy()

    private val generatedM3u8Server by lazy { GeneratedM3u8Server() }

    private val selectedVideoLanguage
        get() = preferences.getString(PREF_KEY_VIDEO_LANGUAGE, DEFAULT_VIDEO_LANGUAGE) ?: DEFAULT_VIDEO_LANGUAGE

    private val mediaUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val noRefererMediaHeaders by lazy {
        Headers.headersOf(
            "User-Agent",
            mediaUserAgent,
        )
    }

    private val siteRefererMediaHeaders by lazy {
        Headers.headersOf(
            "Referer",
            "$baseUrl/",
            "User-Agent",
            mediaUserAgent,
        )
    }

    private val videoResolver by lazy {
        GirigiriloveVideoResolver(
            client = client,
            generatedM3u8Server = generatedM3u8Server,
            headerCandidates = listOf(noRefererMediaHeaders, siteRefererMediaHeaders),
        )
    }

    // ===== Client =================================================================================

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::cookieRetryInterceptor)
        .build()

    private fun cookieRetryInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        if (url == "$baseUrl/" || url == "$baseUrl") {
            return chain.proceed(request)
        }

        var response = chain.proceed(request)
        if (url.contains("/show/") && response.isSuccessful) {
            val body = response.peekBody(1024 * 1024).string()
            if (body.contains("什么都没有") || body.contains(".hl-total').html('0')") || body.contains(".hl-total').html(\"0\")")) {
                response.close()
                // Visit home page to get cookies
                chain.proceed(GET("$baseUrl/", headers)).close()
                // Retry original request
                response = chain.proceed(request)
            }
        }
        return response
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ===== Shared helpers =========================================================================

    private fun String?.toAbsoluteUrl(): String? {
        val url = this?.takeIf { it.isNotBlank() } ?: return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            else -> baseUrl.toHttpUrl().resolve(url)?.toString() ?: url
        }
    }

    // ===== Popular Anime ==========================================================================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/show/2--hits------$page---/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimePage(response)

    // ===== Latest Anime ===========================================================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/show/2--time------$page---/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimePage(response)

    // ===== Search Anime ===========================================================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/index.php/ajax/suggest".toHttpUrl().newBuilder()
                .addQueryParameter("mid", "1")
                .addQueryParameter("wd", query)
                .addQueryParameter("page", "$page")
                .addQueryParameter("limit", "20")
                .build()
            return GET(url.toString(), headers)
        }

        val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.selected ?: "1"
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selected ?: ""
        val year = filters.filterIsInstance<YearFilter>().firstOrNull()?.selected ?: ""
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected ?: "time"

        // Pattern: /show/id-area-by-class-lang-letter-year-month-page-?-?-sort-
        // Slots: 1:id, 3:sort/by, 4:class, 7:year, 9:page
        return GET("$baseUrl/show/$type--$sort-$genre---$year--$page---/", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.encodedPath.contains("suggest")) {
            val suggestResponse = json.decodeFromString<SuggestResponse>(response.body.string())
            val animeList = suggestResponse.list.map {
                SAnime.create().apply {
                    url = "/GV${it.id}/"
                    thumbnail_url = it.pic.toAbsoluteUrl()
                    title = it.name
                }
            }
            return AnimesPage(animeList, suggestResponse.page < suggestResponse.pageCount)
        }
        return parseAnimePage(response)
    }

    private fun parseAnimePage(response: Response): AnimesPage {
        val document = response.asJsoup()

        // Code verification check
        if (document.select("button.verify-submit").isNotEmpty()) {
            throw Exception("请在 WebView 中输入验证码")
        }

        val animeList = document.select(".public-list-box").map {
            SAnime.create().apply {
                val a = it.selectFirst(".public-list-exp")!!
                url = a.attr("href")
                title = a.attr("title")
                thumbnail_url = it.selectFirst("img")?.attr("data-src").toAbsoluteUrl()
            }
        }

        val hasNextPage = document.select(".page-next").isNotEmpty() ||
            document.select(".page-tip:contains(当前)").firstOrNull()?.text()?.let {
                val current = it.substringAfter("当前").substringBefore("/").trim().toIntOrNull()
                val total = it.substringAfter("/").substringBefore("页").trim().toIntOrNull()
                current != null && total != null && current < total
            } ?: false

        return AnimesPage(animeList, hasNextPage)
    }

    // ===== Anime Details ==========================================================================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst(".slide-info-title")?.text() ?: ""
            thumbnail_url = document.selectFirst(".detail-pic img")?.attr("data-src").toAbsoluteUrl()
            description = document.selectFirst("#height_limit.text")?.text()
            genre = document.select(".slide-info:contains(类型 :) a").joinToString { it.text() }
            author = document.select(".slide-info:contains(导演 :) a").joinToString { it.text() }
            artist = document.select(".slide-info:contains(演员 :) a").joinToString { it.text() }
            status = if (document.selectFirst(".slide-info-remarks")?.text()?.contains("完结") == true) {
                SAnime.COMPLETED
            } else {
                SAnime.ONGOING
            }
        }
    }

    // ===== Episode List ===========================================================================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val sources = document.select(".anthology-tab a").map { it.ownText().trim() }
        val playLists = document.select(".anthology-list-play")
        val selectedIndex = sources.indexOfFirst { it.contains(selectedVideoLanguage) }
            .takeIf { it >= 0 }
            ?: 0
        val selectedSource = playLists.getOrNull(selectedIndex) ?: playLists.firstOrNull() ?: return emptyList()
        val sourceName = sources.getOrNull(selectedIndex) ?: sources.firstOrNull() ?: "默认"

        return selectedSource.select("li a").map {
            SEpisode.create().apply {
                name = it.text()
                url = it.attr("href")
                scanlator = sourceName
            }
        }.reversed()
    }

    // ===== Video List =============================================================================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val script = document.select("script:containsData(player_aaaa)").firstOrNull()?.data()
            ?: return emptyList()

        val info = script.substringAfter("player_aaaa=").let { json.parseToJsonElement(it) }
        val encodedUrl = info.jsonObject["url"]?.jsonPrimitive?.content ?: return emptyList()
        val encrypt = info.jsonObject["encrypt"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        val decodedUrl = when (encrypt) {
            1 -> String(Base64.decode(encodedUrl, Base64.DEFAULT))
            2 -> URLDecoder.decode(String(Base64.decode(encodedUrl, Base64.DEFAULT), Charsets.UTF_8), "UTF-8")
            else -> encodedUrl
        }
        val videoUrl = decodedUrl.toAbsoluteUrl() ?: decodedUrl

        val video = videoResolver.resolve(videoUrl)

        return listOf(Video(video.url, "默认", video.url, headers = video.headers))
    }

    override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ===== Filters ================================================================================

    override fun getFilterList() = AnimeFilterList(
        TypeFilter(),
        GenreFilter(),
        YearFilter(),
        SortFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            ListPreference(screen.context).apply {
                key = PREF_KEY_VIDEO_LANGUAGE
                title = "请选择首选视频语言"
                entries = VIDEO_LANGUAGE_OPTIONS
                entryValues = VIDEO_LANGUAGE_OPTIONS
                setDefaultValue(DEFAULT_VIDEO_LANGUAGE)
                summary = "当前选择：$selectedVideoLanguage"
                setOnPreferenceChangeListener { _, newValue ->
                    summary = "当前选择：$newValue"
                    true
                }
            },
        )
    }

    companion object {
        private const val PREF_KEY_VIDEO_LANGUAGE = "PREF_KEY_VIDEO_LANGUAGE"
        private const val DEFAULT_VIDEO_LANGUAGE = "繁中"
        private val VIDEO_LANGUAGE_OPTIONS = arrayOf("繁中", "简中")
    }
}
