package eu.kanade.tachiyomi.animeextension.en.oppaistream

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.oppaistream.dto.AnilistResponseDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState.Companion.STATE_EXCLUDE
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState.Companion.STATE_INCLUDE
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CancellationException
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

@Source
abstract class OppaiStream :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val formBody = FormBody.Builder()
            .add("text", "")
            .add("tags", "")
            .add("blacklist", "")
            .add("studios", "")
            .add("order", "views")
            .add("page", page.toString())
            .add("limit", SEARCH_LIMIT.toString())
            .build()
        return POST("$baseUrl/$SEARCH_PATH", headers, formBody)
    }

    override fun popularAnimeParse(response: Response) = searchAnimeParse(response)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val formBody = FormBody.Builder()
            .add("text", "")
            .add("tags", "")
            .add("blacklist", "")
            .add("studios", "")
            .add("order", "uploaded")
            .add("page", page.toString())
            .add("limit", SEARCH_LIMIT.toString())
            .build()
        return POST("$baseUrl/$SEARCH_PATH", headers, formBody)
    }

    override fun latestUpdatesParse(response: Response) = searchAnimeParse(response)

    // =============================== Search ===============================
    override fun getFilterList() = FILTERS

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var orderVal = "recent"
        val genresInclude = mutableListOf<String>()
        val genresExclude = mutableListOf<String>()
        val studiosList = mutableListOf<String>()

        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> {
                    orderVal = filter.selectedValue()
                }

                is GenreListFilter -> {
                    filter.state.forEach { genreState ->
                        when (genreState.state) {
                            STATE_INCLUDE -> genresInclude.add(genreState.value)
                            STATE_EXCLUDE -> genresExclude.add(genreState.value)
                        }
                    }
                }

                is StudioListFilter -> {
                    filter.state.filter { it.state }.forEach {
                        studiosList.add(it.value)
                    }
                }

                else -> {}
            }
        }

        val formBody = FormBody.Builder()
            .add("text", query.trim())
            .add("tags", genresInclude.joinToString(","))
            .add("blacklist", genresExclude.joinToString(","))
            .add("studios", studiosList.joinToString(","))
            .add("order", orderVal)
            .add("page", page.toString())
            .add("limit", SEARCH_LIMIT.toString())
            .build()

        return POST("$baseUrl/$SEARCH_PATH", headers, formBody)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("a.episode")
        val anime = elements.map(::searchAnimeFromElement).distinctBy { it.url }
        val hasNextPage = elements.size >= SEARCH_LIMIT
        return AnimesPage(anime, hasNextPage)
    }

    private fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img.cover")?.let {
            it.absUrl("src").ifBlank { it.absUrl("data-src") }
        }
        val titleText = element.selectFirst("p.episode-title")?.text()
            ?: element.selectFirst(".title-ep")?.text()
            ?: element.attr("alt")
        title = titleText.replace(TITLE_CLEANUP_REGEX, "").trim()
        setUrlWithoutDomain(element.absUrl("href"))
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.get(baseUrl + anime.url, headers)
        val details = animeDetailsParse(response).apply {
            url = anime.url
            initialized = true
        }

        val useAnilistCover = preferences.getBoolean(PREF_ANILIST_COVER_KEY, PREF_ANILIST_COVER_DEFAULT)
        if (useAnilistCover && details.title.isNotBlank()) {
            val cleanTitle = details.title.replace(TITLE_SANITIZE_REGEX, " ").trim()
            try {
                val anilistCover = fetchThumbnailUrlByTitle(cleanTitle)
                if (anilistCover != null) {
                    val studios = details.artist.orEmpty().split(", ")
                    val matchedStudio = anilistCover.second.find { it in studios }
                    if (matchedStudio != null) {
                        details.thumbnail_url = anilistCover.first
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        return details
    }

    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        setUrlWithoutDomain(response.request.url.toString())
        val document = response.asJsoup()
        val name = document.selectFirst("#episode-title")?.text()?.substringBefore(" Episode")
            ?: document.selectFirst("h1")?.text()?.substringBefore(" Episode")
            ?: ""
        title = name
        description = document.selectFirst("#episode-description")?.text()?.substringAfter("Episode Overview")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")
        genre = document.select(".wrap-genres a[href*='/category/'] p").eachText().joinToString()
        artist = document.select(".wrap-genres a[href*='/studio/'] p").eachText().joinToString()
        thumbnail_url = document.selectFirst("img.cover")?.absUrl("src")
            ?: document.selectFirst("video#episode")?.absUrl("poster")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
    }

    // =============================== Season ===============================
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        var page = 1

        while (true) {
            val formBody = FormBody.Builder()
                .add("text", anime.title)
                .add("tags", "")
                .add("blacklist", "")
                .add("studios", "")
                .add("order", "az")
                .add("page", page.toString())
                .add("limit", "100")
                .build()
            val searchResponse = client.post("$baseUrl/$SEARCH_PATH", headers, formBody).asJsoup()
            val episodeElements = searchResponse.select("a.episode")
            if (episodeElements.isEmpty()) break

            val matched = episodeElements
                .filter {
                    val epTitle = it.selectFirst("p.episode-title")?.text().orEmpty()
                    epTitle.equals(anime.title.trim(), ignoreCase = true)
                }
                .map { element ->
                    SEpisode.create().apply {
                        setUrlWithoutDomain(element.absUrl("href"))
                        val num = element.selectFirst("p.episode-number")?.text()
                            ?: element.attr("epid").ifBlank { "1" }
                        name = "Episode $num"
                        episode_number = num.toFloatOrNull() ?: 1F
                    }
                }
            episodes.addAll(matched)

            if (episodeElements.size < 100) break
            page++
        }

        if (episodes.isNotEmpty()) {
            return episodes.sortedByDescending { it.episode_number }
        }

        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(anime.url)
                name = "Episode 1"
                episode_number = 1F
            },
        )
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val doc = client.get(baseUrl + episode.url, headers).asJsoup()
        val script = doc.selectFirst("script:containsData(var folder)")?.data()
            ?: throw Exception("Could not find player configuration script")
        val folder = FOLDER_REGEX.find(script)?.groupValues?.get(1)
            ?: throw Exception("Could not extract stream folder name")
        val storageBase = STORAGE_BASE_REGEX.find(script)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: "https://oppai.stream/videos"
        val res = RES_REGEX.find(script)?.groupValues?.get(1) ?: "720-1"

        val subtitles = doc.select("track[kind=captions], track[kind=subtitles]").map {
            Track(it.absUrl("src"), it.attr("label"))
        }

        val encodedFolder = folder.replace(" ", "%20")
        val isMp4 = res.contains("-1")
        val ext = if (isMp4) "mp4" else "webm"
        val resolution = when {
            res.startsWith("4k") -> "2160p"
            res.startsWith("1080") -> "1080p"
            res.startsWith("720") -> "720p"
            else -> "720p"
        }
        val videoHeaders = headersBuilder().set("Referer", "$baseUrl/").build()

        val videos = listOf(
            Video(
                videoUrl = "$storageBase/$encodedFolder/ep.$ext",
                videoTitle = "$resolution (Direct)",
                headers = videoHeaders,
                subtitleTracks = subtitles,
            ),
            Video(
                videoUrl = "$storageBase/$encodedFolder/dash/manifest.mpd",
                videoTitle = "$resolution (DASH)",
                headers = videoHeaders,
                subtitleTracks = subtitles,
            ),
        )

        return videos.toHosterList()
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty().sortVideos()

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.videoTitle.contains(quality) },
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ANILIST_COVER_KEY
            title = PREF_ANILIST_COVER_TITLE
            summary = PREF_ANILIST_COVER_SUMMARY
            setDefaultValue(PREF_ANILIST_COVER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_COVER_QUALITY_KEY
            title = PREF_COVER_QUALITY_TITLE
            entries = PREF_COVER_QUALITY_ENTRIES
            entryValues = PREF_COVER_QUALITY_VALUES
            setDefaultValue(PREF_COVER_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    // Function to fetch thumbnail URL using AniList GraphQL API
    private suspend fun fetchThumbnailUrlByTitle(title: String): Pair<String, List<String>>? {
        val query = """
            query {
                Media(search: "$title", type: ANIME, isAdult: true) {
                    coverImage {
                        extraLarge
                        large
                    }
                    studios {
                        nodes {
                            name
                        }
                    }
                }
            }
        """.trimIndent()

        val requestBody = FormBody.Builder()
            .add("query", query)
            .build()

        val response = client.post("https://graphql.anilist.co", headers = headers, body = requestBody)

        return parseThumbnailUrlFromObject(response.parseAs<AnilistResponseDto>())
    }

    private fun parseThumbnailUrlFromObject(obj: AnilistResponseDto): Pair<String, List<String>>? {
        val media = obj.data.media ?: return null

        val coverURL = when (preferences.getString(PREF_COVER_QUALITY_KEY, PREF_COVER_QUALITY_DEFAULT)) {
            "extraLarge" -> media.coverImage.extraLarge
            else -> media.coverImage.large
        }

        val studiosList = media.studios.names

        return Pair(coverURL, studiosList)
    }

    companion object {
        private const val SEARCH_PATH = "api/search.php"
        private const val SEARCH_LIMIT = 36
        private val TITLE_CLEANUP_REGEX = Regex("""\s+\d+$""")
        private val TITLE_SANITIZE_REGEX = Regex("[^a-zA-Z0-9\\s!.:]")

        private val FOLDER_REGEX = Regex("""var\s+folder\s*=\s*["']([^"']+)["']""")
        private val STORAGE_BASE_REGEX = Regex("""var\s+storageBase\s*=\s*["']([^"']+)["']""")
        private val RES_REGEX = Regex("""var\s+res\s*=\s*["']([^"']+)["']""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val PREF_ANILIST_COVER_KEY = "preferred_anilist_cover"
        private const val PREF_ANILIST_COVER_TITLE = "Use Anilist as cover source - Beta"
        private const val PREF_ANILIST_COVER_DEFAULT = true
        private const val PREF_ANILIST_COVER_SUMMARY = "This feature is experimental. " +
            "It enables fetching covers from Anilist. If you see the default cover " +
            "after switching to AniList cover, try clearing the cache in " +
            "Settings > Advanced > Clear Anime Database > Oppai Steam. It only fetch Anilist covers in anime details page."

        private const val PREF_COVER_QUALITY_KEY = "preferred_cover_quality"
        private const val PREF_COVER_QUALITY_TITLE = "Preferred Anilist cover quality - Beta"
        private const val PREF_COVER_QUALITY_DEFAULT = "extraLarge"
        private val PREF_COVER_QUALITY_ENTRIES = arrayOf("Extra Large", "Large")
        private val PREF_COVER_QUALITY_VALUES = arrayOf("extraLarge", "large")
    }
}
