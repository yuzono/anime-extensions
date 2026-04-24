package eu.kanade.tachiyomi.animeextension.id.samehadaku

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMapNotNullBlocking
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Samehadaku :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name: String = "Samehadaku"

    override val baseUrl: String = "https://v2.samehadaku.how"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/daftar-anime-2/page/$page/?order=popular")

    override fun popularAnimeParse(response: Response): AnimesPage = getAnimeParse(response.asJsoup(), "div.relat > article")

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/daftar-anime-2/page/$page/?order=update")

    override fun latestUpdatesParse(response: Response): AnimesPage = getAnimeParse(response.asJsoup(), "div.relat > article")

    // ============================== Related ===============================

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        return document.select(".rand-animesu li").mapNotNull {
            SAnime.create().apply {
                it.selectFirst("a")?.attr("href")?.let { url -> setUrlWithoutDomain(url) } ?: return@mapNotNull null
                title = it.selectFirst(".judul")?.text() ?: return@mapNotNull null
                thumbnail_url = it.selectFirst("img")?.attr("src")
            }
        }
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = SamehadakuFilters.getSearchParameters(filters)
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("daftar-anime-2")
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
            addPathSegment("")
            addQueryParameter("title", query)
        }.build()
        return GET("$url${params.filter}", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val searchSelector = "main.site-main.relat > article"
        return if (doc.selectFirst(searchSelector) != null) {
            getAnimeParse(doc, searchSelector)
        } else {
            getAnimeParse(doc, "div.relat > article")
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = SamehadakuFilters.FILTER_LIST

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val detail = doc.selectFirst("div.infox > div.spe")

        val extractedGenres = doc.select("div.genre-info a")
            .mapNotNull { it.text().takeIf(String::isNotBlank) }
            .joinToString().takeIf(String::isNotBlank)
            ?: detail?.selectFirst("span:has(b:contains(Genre))")?.let { genres: Element ->
                genres.select("a")
                    .mapNotNull { it.text().takeIf(String::isNotBlank) }
                    .joinToString().takeIf(String::isNotBlank)
                    ?: genres.text().substringAfter(":").trim()
            }

        return SAnime.create().apply {
            author = detail?.getInfo("Studio") ?: ""
            status = detail?.let { parseStatus(it.getInfo("Status")) } ?: SAnime.UNKNOWN

            (
                doc.selectFirst("h3.anim-detail")?.text()?.split("Detail Anime")?.getOrNull(1)
                    ?: doc.selectFirst("h2.entry-title[itemprop='partOfSeries']")?.text()?.removeSurrounding("Sinopsis Anime", "Indo")
                    ?: doc.selectFirst("h1.entry-title")?.text()?.removeSuffix("Sub Indo")
                )
                ?.trim()?.let { title = it }

            (
                doc.selectFirst("div.infoanime.widget_senction > div.thumb > img")?.attr("src")
                    ?: doc.selectFirst("div.episodeinf > div.infoanime > div.areainfo > div.thumb > img")?.attr("src")
                )
                .let { thumbnail_url = it }

            (
                doc.selectFirst("div.entry-content.entry-content-single > p")?.text()
                    ?: doc.selectFirst("div.desc > div.entry-content.entry-content-single")?.text()
                )
                .let { description = it }

            extractedGenres?.let { genre = it }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        return doc.select("div.lstepsiode > ul > li")
            .mapNotNull {
                val episode = it.selectFirst("span.eps > a") ?: return@mapNotNull null
                SEpisode.create().apply {
                    setUrlWithoutDomain(episode.attr("href"))
                    episode_number = episode.text().trim().toFloatOrNull() ?: 1F
                    name = it.selectFirst("span.lchx > a")?.text() ?: return@mapNotNull null
                    date_upload = it.selectFirst("span.date")?.text()
                        ?.let { date -> DATE_FORMATTER.tryParse(date) }
                        ?: 0L
                }
            }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val parseUrl = response.request.url.toUrl()
        val url = "${parseUrl.protocol}://${parseUrl.host}"
        return doc.select("#server > ul > li > div")
            .parallelMapNotNullBlocking {
                runCatching { getEmbedLinks(url, it) }.getOrNull()
            }
            .parallelCatchingFlatMapBlocking {
                getVideosFromEmbed(it.first, it.second)
            }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareByDescending { it.quality.contains(quality) })
    }

    private fun Element.getInfo(info: String, cut: Boolean = true): String? = selectFirst("span:has(b:contains($info))")?.text()
        ?.let {
            when {
                cut -> it.substringAfter(" ")
                else -> it
            }.trim()
        }

    private fun getAnimeParse(document: Document, query: String): AnimesPage {
        val animes = document.select(query).mapNotNull { elm ->
            SAnime.create().apply {
                elm.selectFirst("div > a")?.attr("href")?.let { setUrlWithoutDomain(it) } ?: return@mapNotNull null
                title = elm.selectFirst("div.title > h2")?.text() ?: return@mapNotNull null
                elm.selectFirst("div.content-thumb > img")?.attr("src")?.let { thumbnail_url = it }
            }
        }
        val hasNextPage = try {
            val pagination = document.selectFirst("div.pagination")!!
            val totalPage = pagination.selectFirst("span:nth-child(1)")!!.text().split(" ").last()
            val currentPage = pagination.selectFirst("span.page-numbers.current")!!.text()
            currentPage.toInt() < totalPage.toInt()
        } catch (_: Exception) {
            false
        }
        return AnimesPage(animes, hasNextPage)
    }

    private fun parseStatus(status: String?): Int = when (status?.trim()?.lowercase()) {
        "completed" -> SAnime.COMPLETED
        "ongoing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private suspend fun getEmbedLinks(url: String, element: Element): Pair<String, String> {
        val form = FormBody.Builder().apply {
            add("action", "player_ajax")
            add("post", element.attr("data-post"))
            add("nume", element.attr("data-nume"))
            add("type", element.attr("data-type"))
        }.build()

        val ajaxHeaders = headers.newBuilder()
            .set("User-Agent", USER_AGENT)
            .build()

        return client.newCall(POST("$url/wp-admin/admin-ajax.php", body = form, headers = ajaxHeaders))
            .awaitSuccess()
            .bodyString()
            .let {
                val link = srcRegex.find(it)?.groupValues[1]?.takeIf(String::isNotBlank)!!
                val server = element.selectFirst("span")?.text() ?: ""
                Pair(server, link)
            }
    }

    private fun getBloggerVideos(server: String, link: String): List<Video> = bloggerExtractor.videosFromUrl(link, headers, server)

    private suspend fun getVideosFromEmbed(server: String, link: String): List<Video> {
        val videoHeaders = headers.newBuilder()
            .set("User-Agent", USER_AGENT)
            .add("Referer", link)
            .build()

        return when {
            "mega.nz" in link -> emptyList()

            link.contains(".mp4") || link.contains(".webm") || link.contains(".m3u8") -> {
                listOf(Video(link, server, link, videoHeaders))
            }

            "filedon" in link || "uservideo" in link || "userdrive" in link || "samevideo" in link -> {
                val doc = client.newCall(GET(link, videoHeaders)).awaitSuccess().useAsJsoup()
                val dataPage = doc.selectFirst("div#app")?.attr("data-page") ?: return emptyList()
                val json = JSONObject(dataPage)
                val props = json.getJSONObject("props")
                val videoUrl = props.getString("url")
                listOf(Video(videoUrl, server, videoUrl, videoHeaders))
            }

            "krakenfiles" in link -> {
                val doc = client.newCall(GET(link, videoHeaders)).awaitSuccess().useAsJsoup()
                val getUrl = doc.selectFirst("source")?.attr("src") ?: return emptyList()
                val videoUrl = UrlUtils.fixUrl(getUrl)?.replace("&amp;", "&") ?: return emptyList()
                listOf(Video(videoUrl, server, videoUrl, videoHeaders))
            }

            // Blogger — now delegates entirely to BloggerExtractor
            "blogger" in link -> {
                getBloggerVideos(server, link)
            }

            else -> {
                val doc = client.newCall(GET(link, videoHeaders)).awaitSuccess().useAsJsoup()
                val videoUrl = doc.selectFirst("video source")?.attr("src") ?: return emptyList()
                val finalUrl = UrlUtils.fixUrl(videoUrl) ?: return emptyList()
                listOf(Video(finalUrl, server, finalUrl, videoHeaders))
            }
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            summary = "%s"
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
        }
        screen.addPreference(videoQualityPref)
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("d MMMM yyyy", Locale("id", "ID"))
        }
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private val srcRegex by lazy { Regex("""src\s*=\s*["']([^"']+)["']""") }
    }
}
