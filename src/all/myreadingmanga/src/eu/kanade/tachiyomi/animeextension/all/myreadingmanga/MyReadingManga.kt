package eu.kanade.tachiyomi.animeextension.all.myreadingmanga

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

open class MyReadingManga(override val lang: String, private val siteLang: String, private val latestLang: String) :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    /*
     *  ========== Basic Info ==========
     */
    override val name = "MyReadingManga"
    final override val baseUrl = "https://myreadingmanga.info"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.7258.159 Mobile Safari/537.36")
        .add("X-Requested-With", randomString((1..20).random()))

    private val preferences by getPreferencesLazy()

    private val SharedPreferences.username by preferences.delegate(USERNAME_PREF, "")
    private val SharedPreferences.password by preferences.delegate(PASSWORD_PREF, "")

    private var credentials: Credential by LazyMutable { newCredential() }
    private var isLoggedIn = AtomicBoolean(false)

    private data class Credential(val username: String, val password: String)
    private fun newCredential(): Credential {
        isLoggedIn.set(false)
        return Credential(
            username = preferences.username.trim(),
            password = preferences.password.trim(),
        )
    }

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .addInterceptor(::loginInterceptor)
        .build()

    override val supportsLatest = true

    // Login Interceptor
    private fun loginInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            return chain.proceed(request)
        }

        if (isLoggedIn.get()) {
            return chain.proceed(request)
        }

        try {
            val loginForm = FormBody.Builder()
                .add("log", credentials.username)
                .add("pwd", credentials.password)
                .add("wp-submit", "Log In")
                .add("redirect_to", "$baseUrl/")
                .add("testcookie", "1")
                .build()

            val loginRequest = POST("$baseUrl/wp-login.php", headers, loginForm)
            network.client.newCall(loginRequest).execute().use { loginResponse ->
                if (loginResponse.isSuccessful) {
                    isLoggedIn.set(true)
                    return chain.proceed(request)
                } else {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(
                            Injekt.get<Application>(),
                            "MyReadingManga login failed. Please check your credentials.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            return chain.proceed(request)
        } catch (_: Exception) {
            return chain.proceed(request)
        }
    }

    // Preference Screen
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        fun String.usernameSummary() = ifBlank { "Enter your username" }
        fun String.passwordSummary() = if (this.isBlank()) "Enter your password" else "*".repeat(this.length)

        screen.addEditTextPreference(
            key = USERNAME_PREF,
            title = "Username",
            dialogMessage = "Enter your username",
            summary = preferences.username.usernameSummary(),
            getSummary = { it.usernameSummary() },
            default = "",
        ) {
            credentials = newCredential()
        }
        screen.addEditTextPreference(
            key = PASSWORD_PREF,
            title = "Password",
            dialogMessage = "Enter your password",
            summary = preferences.password.passwordSummary(),
            getSummary = { it.passwordSummary() },
            default = "",
        ) {
            credentials = newCredential()
        }
    }

    /*
     *  ========== Popular - Random ==========
     */
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/popular/popular-videos", headers)

    override fun popularAnimeNextPageSelector() = null
    override fun popularAnimeSelector() = ".wpp-list li:has(img[src*=vlcsnap])"
    override fun popularAnimeFromElement(element: Element) = buildAnime(element.select(".wpp-post-title").first()!!, element.select(".wpp-thumbnail").first())
    override fun popularAnimeParse(response: Response): AnimesPage {
        cacheAssistant()
        return super.popularAnimeParse(response)
    }

    /*
     * ========== Latest ==========
     */
    @SuppressLint("DefaultLocale")
    override fun latestUpdatesRequest(page: Int): Request {
        val uri = Uri.parse(baseUrl).buildUpon()
            .appendQueryParameter("ep_filter_lang", latestLang.lowercase())
            .appendQueryParameter("ep_filter_category", "video")
            .appendQueryParameter("s", "")
        if (page > 1) {
            uri.appendQueryParameter("paged", page.toString())
        }
        return GET(uri.toString(), headers)
    }

    override fun latestUpdatesNextPageSelector(): String? = "li.pagination-next"
    override fun latestUpdatesSelector() = "article.category-video"
    override fun latestUpdatesFromElement(element: Element) = buildAnime(element.select("a.entry-title-link").first()!!, element.select("a.entry-image-link img").first())
    override fun latestUpdatesParse(response: Response) = searchAnimeParse(response)

    /*
     * ========== Search ==========
     */
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        val uri = Uri.parse("$baseUrl/page/$page/").buildUpon()
            .appendQueryParameter("ep_filter_category", "video")
            .appendQueryParameter("s", query)
        filterList.filterIsInstance<UriFilter>().forEach { filter ->
            filter.addToUri(uri)
        }
        return GET(uri.toString(), headers)
    }

    override fun searchAnimeNextPageSelector(): String? = "li.pagination-next"
    override fun searchAnimeSelector() = "article.category-video"
    override fun searchAnimeFromElement(element: Element) = buildAnime(element.select("a.entry-title-link").first()!!, element.select("a.entry-image-link img").first())
    override fun searchAnimeParse(response: Response): AnimesPage {
        cacheAssistant()
        return super.searchAnimeParse(response)
    }

    /*
     * ========== Building anime from element ==========
     */
    private fun buildAnime(titleElement: Element, thumbnailElement: Element?): SAnime {
        val anime = SAnime.create().apply {
            setUrlWithoutDomain(titleElement.attr("href"))
            title = cleanTitle(titleElement.text())
            thumbnailElement?.getImage()?.getThumbnail()?.let { thumbnail_url = it }
        }
        return anime
    }

    private val extensionRegex = Regex("""\.(jpg|png|jpeg|webp)""")

    private fun Element.getImage(): String? {
        val url = when {
            attr("data-src").contains(extensionRegex) -> attr("abs:data-src")
            attr("data-cfsrc").contains(extensionRegex) -> attr("abs:data-cfsrc")
            attr("src").contains(extensionRegex) -> attr("abs:src")
            else -> attr("abs:data-lazy-src")
        }

        return if (URLUtil.isValidUrl(url)) url else null
    }

    // removes resizing
    private fun String.getThumbnail(): String? {
        val url = substringBeforeLast("-") + "." + substringAfterLast(".")
        return if (URLUtil.isValidUrl(url)) url else null
    }
    private val titleRegex = Regex("""\s*\[[^]]*]\s*|\s*\(\d{4}\)\s*""")
    private fun cleanTitle(title: String): String {
        var cleanedTitle = title
        cleanedTitle = cleanedTitle.substringAfter(": ", cleanedTitle).trimStart()
        cleanedTitle = cleanedTitle.replace(titleRegex, " ").trim()
        if (cleanedTitle.endsWith(")") && cleanedTitle.lastIndexOf('(') != -1) {
            cleanedTitle = cleanedTitle.substringBeforeLast("(").trimEnd()
        }
        return cleanedTitle.replace(Regex("\\s+"), " ").trim()
    }

    // Anime Details
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val needCover = anime.thumbnail_url?.let { url ->
            client.newCall(GET(url, headers)).await().use { !it.isSuccessful }
        } ?: true

        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        return animeDetailsParse(response.asJsoup(), needCover).apply { initialized = true }
    }

    private suspend fun animeDetailsParse(document: Document, needCover: Boolean = true): SAnime = SAnime.create().apply {
        title = cleanTitle(document.select("h1").text())
        author = document.select(".entry-terms a[href*=artist]").firstOrNull()?.text()
        artist = author
        genre = document.select(".entry-header p a[href*=genre], [href*=tag], span.entry-categories a").joinToString { it.text() }
        val basicDescription = document.select("h1").text()
        // too troublesome to achieve 100% accuracy assigning scanlator group during episodeListParse
        val scanlatedBy = document.select(".entry-terms:has(a[href*=group])").firstOrNull()
            ?.select("a[href*=group]")?.joinToString(prefix = "Scanlated by: ") { it.text() }
        val extendedDescription = document.select(".entry-content p:not(p:containsOwn(|)):not(.chapter-class + p)").joinToString("\n") { it.text() }
        description = listOfNotNull(basicDescription, scanlatedBy, extendedDescription).joinToString("\n").trim()
        status = when (document.select("a[href*=status]").first()?.text()) {
            "Completed" -> SAnime.COMPLETED
            "Ongoing" -> SAnime.ONGOING
            "Licensed" -> SAnime.LICENSED
            "Dropped" -> SAnime.CANCELLED
            "Discontinued" -> SAnime.CANCELLED
            "Hiatus" -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }

        if (needCover) {
            client.newCall(GET("$baseUrl/search/?search=${document.location()}", headers))
                .awaitSuccess()
                .use { response ->
                    response.asJsoup().selectFirst("div.wdm_results div.p_content img")
                        ?.getImage()?.getThumbnail()
                        ?.let { thumbnail_url = it }
                }
        }
    }

    override fun animeDetailsParse(document: Document) = throw UnsupportedOperationException()

    /*
     * ========== Building episodes from element ==========
     */
    override fun episodeListSelector() = "a[class=page-numbers]"

    @SuppressLint("DefaultLocale")
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        val date = parseDate(document.select(".entry-time").text())
        // create first episode since its on main anime page
        episodes.add(createEpisode("1", document.baseUri(), date, "Ep. 1"))
        // see if there are multiple episodes or not
        val lastEpisodeNumber = document.select(episodeListSelector()).last()?.text()?.toIntOrNull()
        if (lastEpisodeNumber != null) {
            // There are entries with more episodes but those never show up,
            // so we take the last one and loop it to get all hidden ones.
            // Example: 1 2 3 4 .. 7 8 9 Next
            for (i in 2..lastEpisodeNumber) {
                episodes.add(createEpisode(i.toString(), document.baseUri(), date, "Ep. $i"))
            }
        }
        return episodes.reversed()
    }

    private fun parseDate(date: String): Long = SimpleDateFormat("MMM dd, yyyy", Locale.US).parse(date)?.time ?: 0

    private fun createEpisode(pageNumber: String, animeUrl: String, date: Long, epname: String): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain("$animeUrl/$pageNumber")
        episode.name = epname
        episode.date_upload = date
        return episode
    }

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    /*
     * ========== Building videos from element ==========
     */

    override fun videoListSelector(): String = "div.video-container-ads video source"

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = document.selectFirst(videoListSelector())?.attr("src")
        ?: throw Exception("No video URL found")

    override fun videoListParse(response: Response): List<Video> {
        if (!response.isSuccessful) {
            if (response.code == 403) {
                throw Exception("Download the episode before watching (Error 403)")
            }
            throw Exception("Failed to fetch video list: HTTP ${response.code}")
        }

        val document = response.asJsoup()
        val videoUrl = videoUrlParse(document)
        if (videoUrl.isEmpty()) return emptyList()

        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(videoUrl) ?: ""

        val baseHeaders = headersBuilder().build()
        val customHeaders = baseHeaders.newBuilder().apply {
            set("Cookie", cookies)
        }.build()

        return listOf(Video(videoUrl, "Default", videoUrl, customHeaders))
    }

    /*
     * ========== Parse filters from pages ==========
     *
     * In a recent (2025) update, MRM updated their search interface. As such, there is no longer
     * pages listing every tags, every author, etc. (except for Langs and Genres). The search page
     * display the top 25 results for each filter category. Since these lists aren't exhaustive, we
     * call them "Popular"
     *
     * TODO : MRM have a meta sitemap (https://myreadingmanga.info/sitemap_index.xml) that links to
     * tag/genre/pairing/etc xml sitemaps. Filters could be populated from those instead of HTML pages
     */
    private var filtersCached = false
    private var mainPage = ""
    private var searchPage = ""

    // Grabs page containing filters and puts it into cache
    private fun filterAssist(url: String): String {
        return client.newCall(GET(url, headers)).execute().use {
            if (!it.isSuccessful) return ""
            it.body.string()
        }
    }

    private fun cacheAssistant() {
        if (!filtersCached) {
            mainPage = filterAssist(baseUrl)
            searchPage = filterAssist("$baseUrl/?s=")
            filtersCached = true
        }
    }

    // Parses main page for filters
    private fun getFiltersFromMainPage(@Suppress("SameParameterValue") filterTitle: String): List<MrmFilter> {
        val document = if (mainPage == "") {
            filtersCached = false
            null
        } else {
            filtersCached = true
            Jsoup.parse(mainPage)
        }
        val parent = document?.select(".widget-title")?.firstOrNull { it.text() == filterTitle }?.parent()
        return parent?.select(".tag-cloud-link")
            ?.map { MrmFilter(it.text(), it.attr("href").split("/").reversed()[1]) }
            ?: listOf(MrmFilter("Press 'Reset' to load filters", ""))
    }

    // Parses search page for filters
    private fun getFiltersFromSearchPage(filterTitle: String, isSelectDropdown: Boolean = false): List<MrmFilter> {
        val document = if (searchPage == "") {
            filtersCached = false
            null
        } else {
            filtersCached = true
            Jsoup.parse(searchPage)
        }
        val parent = document?.select(".ep-filter-title")?.firstOrNull { it.text() == filterTitle }?.parent()

        val filters: List<MrmFilter>? = if (isSelectDropdown) {
            parent?.select("option")?.map { MrmFilter(it.text(), it.attr("value")) }
        } else {
            parent?.select(".term")?.map { MrmFilter(it.text(), it.attr("data-term-slug")) }
        }

        return filters ?: listOf(MrmFilter("Press 'Reset' to load filters", ""))
    }

    // Generates the filter lists for app
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        EnforceLanguageFilter(siteLang),
        SearchSortTypeList(getFiltersFromSearchPage("Sort by", true)),
        GenreFilter(getFiltersFromMainPage("Genres")),
        CatFilter(getFiltersFromSearchPage("Category")),
        TagFilter(getFiltersFromSearchPage("Tag")),
        ArtistFilter(getFiltersFromSearchPage("Circle/ artist")),
        PairingFilter(getFiltersFromSearchPage("Pairing")),
        StatusFilter(getFiltersFromSearchPage("Status")),
    )

    private class EnforceLanguageFilter(val siteLang: String) :
        AnimeFilter.CheckBox("Enforce language", true),
        UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            if (state) uri.appendQueryParameter("ep_filter_lang", siteLang)
        }
    }

    private class SearchSortTypeList(sorts: List<MrmFilter>) : UriSelectOneFilter("Sort by", "ep_sort", sorts)
    private class GenreFilter(genres: List<MrmFilter>) : UriSelectFilter("Genre", "ep_filter_genre", genres)
    private class CatFilter(catIds: List<MrmFilter>) : UriSelectFilter("Popular Categories", "ep_filter_category", catIds)
    private class TagFilter(popularTags: List<MrmFilter>) : UriSelectFilter("Popular Tags", "ep_filter_post_tag", popularTags)
    private class ArtistFilter(popularArtists: List<MrmFilter>) : UriSelectFilter("Popular Artists", "ep_filter_artist", popularArtists)
    private class PairingFilter(pairs: List<MrmFilter>) : UriSelectFilter("Popular Pairings", "ep_filter_pairing", pairs)
    private class StatusFilter(status: List<MrmFilter>) : UriSelectFilter("Status", "ep_filter_status", status)

    private class MrmFilter(name: String, val value: String) : AnimeFilter.CheckBox(name)
    private open class UriSelectFilter(
        displayName: String,
        val uriParam: String,
        vals: List<MrmFilter>,
    ) : AnimeFilter.Group<MrmFilter>(displayName, vals),
        UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            val checked = state.filter { it.state }.ifEmpty { return }
                .joinToString(",") { it.value }

            uri.appendQueryParameter(uriParam, checked)
        }
    }

    private open class UriSelectOneFilter(
        displayName: String,
        val uriParam: String,
        val vals: List<MrmFilter>,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.name }.toTypedArray(), defaultValue),
        UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            if (state != 0) {
                uri.appendQueryParameter(uriParam, vals[state].value)
            }
        }
    }

    /**
     * Represents a filter that is able to modify a URI.
     */
    private interface UriFilter {
        fun addToUri(uri: Uri.Builder)
    }

    companion object {
        private const val USERNAME_PREF = "MYREADINGMANGA_USERNAME"
        private const val PASSWORD_PREF = "MYREADINGMANGA_PASSWORD"
    }

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }
}
