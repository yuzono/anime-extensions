package eu.kanade.tachiyomi.animeextension.all.hentaitorrent

import android.annotation.SuppressLint
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.torrentutils.TorrentUtils
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.SocketTimeoutException
import java.util.Locale

@Source
abstract class HentaiTorrent :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    private val preferences by getPreferencesLazy()

    override val supportsLatest = false

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page")

    override fun popularAnimeSelector(): String = "div.image-container div.image-wrapper"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a.overlay").attr("href"))
        anime.title = element.select("a.overlay").text().trim()
        anime.thumbnail_url = element.select("img").attr("src")

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination a:contains(Next)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = query.toHttpUrlOrNull()
        if (url != null && url.host.removePrefix("www.") == baseUrl.toHttpUrl().host.removePrefix("www.")) {
            val path = url.encodedPath
            if (!path.endsWith(".html")) throw Exception("Unsupported url")
            return client.newCall(GET(baseUrl + path, headers))
                .awaitSuccess()
                .use { searchAnimeByUrlParse(it, path) }
        }
        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByUrlParse(response: Response, path: String): AnimesPage {
        val document = response.asJsoup()
        val anime = animeDetailsParse(document).apply {
            url = path
            title = document.selectFirst("h1")!!.text()
            thumbnail_url = document.selectFirst("div.categories ~ img")?.absUrl("src")?.toListingThumbnail()
        }
        return AnimesPage(listOf(anime), false)
    }

    // ".../pic/<yyyymm>/<dd>/<name>.<ext>" -> the listing's "$baseUrl/mini/<yyyymm>/<name>-500.<ext>"
    private fun String.toListingThumbnail(): String {
        val segments = toHttpUrlOrNull()?.pathSegments ?: return this
        if (segments.size != 4 || segments[0] != "pic" || '.' !in segments[3]) return this
        val file = segments[3]
        return "$baseUrl/mini/${segments[1]}/${file.substringBeforeLast('.')}-500.${file.substringAfterLast('.')}"
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.replace(" ", "+")
        val cat = filters.firstNotNullOfOrNull { filter ->
            if (filter is CategoriesList) getCategory()[filter.state].id else 0
        }.toString()

        return if (query.isNotEmpty()) {
            GET("$baseUrl/s.php?search=$encodedQuery&page=$page")
        } else {
            GET("$baseUrl/catalog/$cat/page/$page")
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.description = document.select("div.article-content").html().replace(Regex("<(?!br\\s*/?)[^>]+>"), "").replace("<br>", "\n").replace("<br/>", "\n")
        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.download-container div.download-button"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val downloadButtonUrl = document.select("div.download-container a.download-button").attr("href")
        val downloadPage = client.newCall(GET("$baseUrl$downloadButtonUrl")).execute().asJsoup()
        val torrentFileUrl = downloadPage.select("a.download-button").attr("href")

        if (torrentFileUrl.isEmpty()) throw Exception("No Torrent Found!")

        return try {
            val torrent = TorrentUtils.getTorrentInfo(torrentFileUrl, "torrent")
            val torrentMagnetLink = "magnet:?xt=urn:btih:${torrent.hash}&dn=${torrent.hash}"
            var torrentTrackers = fetchTrackers().split("\n").filter { it.isNotBlank() }.joinToString("&tr=", "&tr=")
            torrentTrackers += torrent.trackers.filter { it.isNotBlank() }.joinToString("&tr=", "&tr=")
            var episodeNumber = 1F
            torrent.files
                .filter { it.path.substringAfterLast('.').lowercase(Locale.ROOT) in validVideoExtensions }
                .map { file ->
                    SEpisode.create().apply {
                        name = if (preferences.getBoolean(IS_FILENAME_KEY, IS_FILENAME_DEFAULT)) {
                            file.path.split('/').last().trim()
                        } else {
                            file.path.trim().replace("[", "(").replace("]", ")").replace("/", "\uD83D\uDCC2 ")
                        }
                        url = "$torrentMagnetLink$torrentTrackers&index=${file.indexFile}"
                        episode_number = episodeNumber++
                        scanlator = convertBytesToReadable(file.size)
                    }
                }.reversed()
        } catch (_: SocketTimeoutException) {
            throw Exception("Dead Torrent \uD83D\uDE35")
        }
    }

    private val validVideoExtensions = setOf("mp4", "mov", "avi", "wmv", "mkv", "flv", "webm", "mpeg", "mpg", "mts", "vob", "ts")

    @SuppressLint("DefaultLocale")
    private fun convertBytesToReadable(bytes: Long): String {
        val kilobytes = bytes / 1024.0
        val megabytes = kilobytes / 1024.0
        val gigabytes = megabytes / 1024.0

        return when {
            gigabytes >= 1 -> String.format("%.2f GB", gigabytes)
            megabytes >= 1 -> String.format("%.2f MB", megabytes)
            else -> String.format("%.2f KB", kilobytes)
        }
    }

    private fun fetchTrackers(): String {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/ngosang/trackerslist/refs/heads/master/trackers_all_http.txt")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Unexpected code $response")
            return response.body.string().trim()
        }
    }

    override fun episodeFromElement(element: Element) = throw Exception("Not used")

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = listOf(Video(episode.url, episode.name, episode.url))

    override fun videoListSelector() = throw Exception("Not used")

    override fun videoFromElement(element: Element) = throw Exception("Not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = IS_FILENAME_KEY
            title = "Only display filename"
            setDefaultValue(IS_FILENAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "Will note display full path of episode."
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_IMG_KEY
            title = "Display Images in episode list."
            setDefaultValue(IS_IMG_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "Its an experimental option."
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_AUDIO_KEY
            title = "Display Audio in episode list."
            setDefaultValue(IS_AUDIO_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "Its an experimental option."
        }.also(screen::addPreference)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoriesList(categoryName),
    )

    private data class Category(val name: String, val id: String?)
    private class CategoriesList(category: Array<String>) : AnimeFilter.Select<String>("category", category)
    private val categoryName = getCategory().map {
        it.name
    }.toTypedArray()
    private fun getCategory(): List<Category> = listOf(
        Category("Home", "0"),
        Category("Cartoons", "Cartoons"),
        Category("2D video Hentai", "2D%20video%20Hentai"),
        Category("3D video Hentai", "3D%20video%20Hentai"),
        Category("Hentai DVD HD", "Hentai%20DVD%20HD"),
        Category("Main Subsection Hentai", "Main%20Subsection%20Hentai"),
    )

    companion object {
        private const val IS_FILENAME_KEY = "filename"
        private const val IS_FILENAME_DEFAULT = false

        private const val IS_IMG_KEY = "img"
        private const val IS_IMG_DEFAULT = false

        private const val IS_AUDIO_KEY = "audio"
        private const val IS_AUDIO_DEFAULT = false
    }
}
