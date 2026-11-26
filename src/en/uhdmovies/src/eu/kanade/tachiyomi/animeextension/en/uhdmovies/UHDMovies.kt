package eu.kanade.tachiyomi.animeextension.en.uhdmovies

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.drivelinkresolver.DriveLinkResolver
import keiyoushi.network.get
import keiyoushi.network.head
import keiyoushi.network.post
import keiyoushi.source.AniSource
import keiyoushi.source.model.SAnimeUpdate
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class UHDMovies :
    AniSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    private val driveLinkResolver by lazy { DriveLinkResolver(client, headers) }

    // Keeps the Instant link's redirect target visible
    private val noRedirectClient by lazy { client.newBuilder().followRedirects(false).build() }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage = client.get("$baseUrl/page/$page/", headers)
        .asJsoup()
        .toAnimesPage()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("page/$page/")
            .addQueryParameter("s", query.trim())
            .build()
        return client.get(url, headers).asJsoup().toAnimesPage()
    }

    private fun Document.toAnimesPage(): AnimesPage = AnimesPage(
        select(ANIME_SELECTOR).mapNotNull(::animeFromElement),
        selectFirst(NEXT_PAGE_SELECTOR) != null,
    )

    private fun animeFromElement(element: Element): SAnime? = SAnime.create().apply {
        val link = element.selectFirst("div.entry-image > a") ?: return null
        setUrlWithoutDomain(link.absUrl("href"))
        thumbnail_url = link.selectFirst("img")?.absUrl("src")
        title = link.attr("title").cleanTitle()
    }

    // ============================ Anime Update ============================
    // Details and episodes come from the same page, so it is fetched only once
    override suspend fun fetchAnimeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeUpdate {
        if (!fetchDetails && !fetchEpisodes) return SAnimeUpdate(anime, episodes)

        val document = client.get(baseUrl + anime.url, headers).asJsoup()
        return SAnimeUpdate(
            anime = if (fetchDetails) parseDetails(document, anime) else anime,
            episodes = if (fetchEpisodes) parseEpisodes(document) else episodes,
        )
    }

    private fun parseDetails(document: Document, anime: SAnime): SAnime = SAnime.create().apply {
        url = anime.url
        title = document.selectFirst("h1.entry-title")?.text()?.cleanTitle() ?: anime.title
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() } ?: anime.thumbnail_url
        description = document.selectFirst("pre:contains(plot)")?.text()
            ?: document.selectFirst("div.entry-content > p")?.text()?.takeIf { it.isNotBlank() }
        genre = document.selectFirst("div.entry-header div.entry-category, div.entry-category")
            ?.select("a")
            ?.joinToString { it.text() }
            ?.takeIf { it.isNotBlank() }
        status = SAnime.COMPLETED
        initialized = true
    }

    private fun Regex.firstValue(text: String) = find(text)?.groupValues?.get(1)?.let { Pair(text, it) }

    private fun parseEpisodes(doc: Document): List<SEpisode> {
        val rows = doc.select(EPISODE_ROW_SELECTOR)
        val isSeries = rows.firstOrNull()?.text().orEmpty().let { text ->
            listOf("Episode", "Zip", "Pack").any { text.contains(it, true) }
        }

        val links = rows.flatMap { row ->
            val prevP = row.previousElementSibling()?.text().orEmpty()
            val quality = (QUALITY_REGEX.find(prevP) ?: QUALITY_REGEX.find(row.text()))?.value ?: "HD"
            val size = SIZE_REGEX.find(prevP)?.groupValues?.get(1)
            val release = prevP.replace(SIZE_REGEX, "").trim().ifEmpty { null }
            val name = if (isSeries) seasonName(row, prevP, doc) else movieName(row, quality)

            row.select("a[href*=?sid=], a[href*=r?key=]")
                .filterNot { el -> el.classNames().any { it.endsWith("-zip") } }
                .mapIndexed { index, el ->
                    val episode = el.text().replace("Episode", "", true).trim().toIntOrNull() ?: (index + 1)
                    (name to episode) to EpUrl(quality, el.attr("href"), size, release)
                }
        }

        return links.groupBy({ it.first }, { it.second }).entries.mapIndexed { index, (key, urls) ->
            SEpisode.create().apply {
                url = EpLinks(urls).toJsonString()
                name = if (isSeries) "${key.first} Ep ${key.second}" else key.first
                episode_number = (if (isSeries) key.second else index + 1).toFloat()
            }
        }.reversed()
    }

    private fun seasonName(row: Element, prevP: String, doc: Document): String {
        val prevPre = row.previousElementSiblings().prev("pre,div.mks_separator").first()?.text().orEmpty()
        val title = doc.selectFirst("h1.entry-title")?.text().orEmpty()
        val (source, season) = SEASON_REGEX.firstValue(prevP)
            ?: SEASON_REGEX.firstValue(prevPre)
            ?: SEASON_TITLE_REGEX.firstValue(title)
            ?: ("" to "1")
        val part = PART_REGEX.find(source)?.groupValues?.get(1)?.let { " Pt $it" }.orEmpty()
        return "Season ${season.toIntOrNull() ?: 1}$part"
    }

    private fun movieName(row: Element, quality: String): String {
        val heading = row.previousElementSiblings().prev("h1,h2,h3,pre:not(:contains(plot))").first()?.text()
        val name = (heading ?: "Movie - $quality").cleanTitle()
        return if (name.contains("Collection", true)) row.previousElementSibling()?.ownText()?.ifBlank { null } ?: name else name
    }

    // Episodes store their download links, so WebView opens the first one
    override fun getEpisodeUrl(episode: SEpisode): String = episode.url.parseAs<EpLinks>().urls.firstOrNull()?.url ?: baseUrl

    // ============================== Hosters ===============================
    // Every release is a separate file, so each one is a hoster
    override suspend fun fetchHosterList(episode: SEpisode): List<Hoster> = episode.url.parseAs<EpLinks>().urls.map {
        Hoster(
            hosterUrl = it.url,
            hosterName = listOfNotNull(it.quality, it.size, it.release).joinToString(" • "),
            internalData = it.toJsonString(),
        )
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val ascending = preferences.getString(PREF_SIZE_SORT_KEY, PREF_SIZE_SORT_DEFAULT)!! == "asc"

        val bySize = compareBy<Hoster> { it.data().size.sizeInMb() }
        return sortedWith(
            compareByDescending<Hoster> { it.data().quality.contains(quality, ignoreCase = true) }
                .then(if (ascending) bySize else bySize.reversed()),
        )
    }

    private fun Hoster.data(): EpUrl = internalData.parseAs()

    // =============================== Videos ===============================
    override suspend fun fetchVideoList(hoster: Hoster): List<Video> {
        val link = hoster.data()
        val doc = fetchDoc(driveLinkResolver.fileUrl(link.url) ?: throw Exception("Could not bypass the download link"))
        if (doc.selectFirst("div.card-body:contains(no longer available)") != null) throw Exception("This file has been removed")

        val size = doc.selectFirst("li.list-group-item:contains(Size :)")?.text()?.substringAfter(":")?.trim() ?: link.size
        fun title(type: String) = listOfNotNull(link.quality, type, size).joinToString(" - ")

        val cloud = doc.link(CLOUD_PAGE_SELECTOR)?.let { cloudLink(it) }
            ?.let { Video(videoUrl = it, videoTitle = title("Cloud Resume")) }
        val instant = doc.link(INSTANT_SELECTOR)?.let { instantLink(it) }
            ?.let { Video(videoUrl = it, videoTitle = title("Instant")) }
        return listOfNotNull(cloud, instant)
            .ifEmpty { throw Exception("No ready link yet: the cloud copy is being generated, try again in a few minutes") }
    }

    // Files not cached yet only offer "Generate Cloud Link": start it, the link is ready a few minutes later
    private suspend fun cloudLink(url: String): String? {
        val doc = fetchDoc(url)
        doc.link(CLOUD_SELECTOR)?.let { return it }
        val key = CLOUD_KEY_REGEX.find(doc.html())?.groupValues?.get(1) ?: return null
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("action", "cloud")
            .addFormDataPart("key", key)
            .addFormDataPart("action_token", "")
            .build()
        val generateHeaders = headers.newBuilder().set("x-token", doc.location().toHttpUrl().host).build()
        // Opening the returned page, as the site's button does, makes sure generation starts
        runCatching {
            val response = client.post(url, generateHeaders, body, ensureSuccess = false).parseAs<JsonObject>()
            (response["visit_url"] ?: response["url"])?.stringOrNull?.let { client.get(it, headers, CacheControl.FORCE_NETWORK).close() }
        }
        return null
    }

    // Instant links are shared and stop working after about one full download, so dead ones are left out
    private suspend fun instantLink(instant: String): String? {
        // Not followed: video-seed.dev only shows its "url" parameter as a download button
        val location = noRedirectClient.get(instant, headers, CacheControl.FORCE_NETWORK, ensureSuccess = false)
            .use { it.request.url.resolve(it.header("Location") ?: return null) } ?: return null
        val url = location.queryParameter("url") ?: location.toString()
        return url.takeIf { client.head(url, headers, CacheControl.FORCE_NETWORK, ensureSuccess = false).use { it.isSuccessful } }
    }

    private suspend fun fetchDoc(url: String): Document = client.get(url, headers, CacheControl.FORCE_NETWORK).asJsoup()

    private fun Document.link(selector: String): String? = selectFirst(selector)?.absUrl("href")?.ifEmpty { null }

    // ============================= Utilities ==============================
    private fun String.cleanTitle(): String = replace("Download", "", true).trim()

    private fun String?.sizeInMb(): Float {
        val match = this?.let(SIZE_VALUE_REGEX::find) ?: return 0F
        val value = match.groupValues[1].toFloatOrNull() ?: return 0F
        return if (match.groupValues[2].equals("GB", true)) value * 1000 else value
    }

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_SIZE_SORT_KEY,
            title = "Preferred size sort",
            entries = listOf("Ascending", "Descending"),
            entryValues = listOf("asc", "desc"),
            default = PREF_SIZE_SORT_DEFAULT,
            summary = "%s\nOrder of the files after they are sorted by quality.",
        )
    }

    @Serializable
    class EpLinks(val urls: List<EpUrl>)

    @Serializable
    class EpUrl(
        val quality: String,
        val url: String,
        val size: String? = null,
        val release: String? = null,
    )

    companion object {
        private const val ANIME_SELECTOR = "div#content div.gridlove-posts > div.layout-masonry"
        private const val NEXT_PAGE_SELECTOR = "div#content nav.gridlove-pagination a.next"
        private const val EPISODE_ROW_SELECTOR =
            "p:has(a[href*=?sid=],a[href*=r?key=]):has(a[class*=maxbutton])[style*=center]"
        private const val CLOUD_PAGE_SELECTOR = "a[href*=/zfile/]"
        private const val CLOUD_SELECTOR = "div.card-body a[href*=workers.dev], div.card-body a.btn-success[href]"
        private const val INSTANT_SELECTOR = "div.card-body a[href*=video-gen], div.card-body a.btn-danger[href^=http]"
        private val CLOUD_KEY_REGEX = Regex("""append\("key",\s*"([^"]+)"""")

        private val QUALITY_REGEX = Regex("""\d{3,4}p""", RegexOption.IGNORE_CASE)
        private val SEASON_REGEX = Regex("""[ .]?S(?:eason)?[ .]?(\d{1,2})[ .]?""", RegexOption.IGNORE_CASE)
        private val SEASON_TITLE_REGEX = Regex("""[ .\[(]?S(?:eason)?[ .]?(\d{1,2})[ .\])]?""", RegexOption.IGNORE_CASE)
        private val PART_REGEX = Regex("""Part ?(\d{1,2})""", RegexOption.IGNORE_CASE)
        private val SIZE_REGEX = Regex("""\[([\d.]+\s*[GM]B)]""", RegexOption.IGNORE_CASE)
        private val SIZE_VALUE_REGEX = Regex("""([\d.]+)\s*([GM]B)""", RegexOption.IGNORE_CASE)

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("2160p", "1080p", "720p", "480p")

        private const val PREF_SIZE_SORT_KEY = "preferred_size_sort"
        private const val PREF_SIZE_SORT_DEFAULT = "asc"
    }
}
