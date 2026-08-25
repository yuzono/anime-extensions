package eu.kanade.tachiyomi.animeextension.en.anineko

import android.net.Uri
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class AniNeko :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniNeko"

    override val baseUrl = "https://anineko.to"

    override val lang = "en"

    override val supportsLatest = true

    override val disableRelatedAnimesBySearch = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    private val localProxy by lazy { LocalProxy(client) }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================= Popular ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browse?sort=release_date&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    // ============================= Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browse?sort=recently_updated&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$baseUrl/browse".toHttpUrl().newBuilder()
        urlBuilder.addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("keyword", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is Filters.GenreFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("genre[]", it)
                    }
                }

                is Filters.TypeFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("type[]", it)
                    }
                }

                is Filters.StatusFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("status[]", it)
                    }
                }

                is Filters.LanguageFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("language[]", it)
                    }
                }

                is Filters.YearFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("year[]", it)
                    }
                }

                is Filters.SortFilter -> {
                    if (!filter.isDefault()) {
                        urlBuilder.addQueryParameter("sort", filter.toUriPart())
                    }
                }

                else -> {}
            }
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val cards = document.select("article.nv-anime-card.nv-browse-card")

        val animes = cards.map { card ->
            SAnime.create().apply {
                val linkEl = card.selectFirst("a.nv-anime-thumb") ?: card.selectFirst("a")!!
                url = linkEl.attr("href")
                title = card.selectFirst("h3.nv-anime-title a")?.text()
                    ?: linkEl.selectFirst("img")?.attr("alt")
                    ?: ""
                thumbnail_url = linkEl.selectFirst("img")?.attr("src")
            }
        }

        val hasNextPage = document.selectFirst("li.page-item.next") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ============================= Filters ==============================

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    open class CheckBoxFilterList(name: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, vals.map { CheckBoxVal(it.first, false) }) {
        fun getCheckedUriParts(): List<String> = state.mapIndexedNotNull { index, checkbox ->
            if (checkbox.state) vals[index].second else null
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.SortFilter(),
        AnimeFilter.Separator(),
        Filters.GenreFilter(),
        AnimeFilter.Separator(),
        Filters.TypeFilter(),
        AnimeFilter.Separator(),
        Filters.StatusFilter(),
        AnimeFilter.Separator(),
        Filters.LanguageFilter(),
        AnimeFilter.Separator(),
        Filters.YearFilter(),
    )

    // =========================== Anime Details ==========================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            val titleLang = preferences.getString(TITLE_LANG_KEY, TITLE_LANG_DEFAULT)!!
            val mainTitle = document.selectFirst("h1")?.text() ?: ""
            val altTitle = document.selectFirst("div.nv-info-alt-title")?.text() ?: ""
            title = if (titleLang == "Romaji/Japanese" && altTitle.isNotBlank()) {
                altTitle
            } else {
                mainTitle
            }

            genre = document.select("div.nv-info-genres span").joinToString { it.text() }

            val statusStr = document.selectFirst("div.nv-info-list div:contains(Status) strong, div.nv-info-stats div:contains(Status) strong")?.text() ?: ""
            status = when {
                statusStr.contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
                statusStr.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }

            author = document.selectFirst("div.nv-info-list div:contains(Studios) strong a")?.text()
            thumbnail_url = document.selectFirst("aside.nv-info-poster img")?.attr("src")

            val baseDesc = document.selectFirst("p.nv-info-desc, div.nv-info-synopsis p")?.text() ?: ""
            description = if (altTitle.isNotBlank()) {
                "$baseDesc\n\nAlternative Title: $altTitle"
            } else {
                baseDesc
            }
        }
    }

    // =========================== Related Anime ==========================

    override fun relatedAnimeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.useAsJsoup()
        return document.select("div.nv-info-related-list article").mapNotNull { article ->
            val linkEl = article.selectFirst("a")
            val url = linkEl?.attr("href") ?: return@mapNotNull null
            val title = article.selectFirst("h3 a")?.text()
                ?: linkEl.selectFirst("img")?.attr("alt")
                ?: return@mapNotNull null
            val thumbnail = linkEl.selectFirst("img")?.attr("src")

            SAnime.create().apply {
                this.url = url
                this.title = title
                this.thumbnail_url = thumbnail
            }
        }
    }

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = coroutineScope {
        val explicitRelated = try {
            val response = client.newCall(relatedAnimeListRequest(anime)).awaitSuccess()
            relatedAnimeListParse(response)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }

        val titleWords = anime.title.split(" ")
            .map { it.filter { c -> c.isLetterOrDigit() } }
            .filter { it.length >= 2 }
            .take(3)

        val titleSearch = async {
            if (titleWords.isEmpty()) return@async emptyList()
            val query = titleWords.joinToString(" ")
            try {
                val searchUrl = "$baseUrl/browse".toHttpUrl().newBuilder().apply {
                    addQueryParameter("page", "1")
                    addQueryParameter("keyword", query)
                }.build()
                val resp = client.newCall(GET(searchUrl, headers)).awaitSuccess()
                searchAnimeParse(resp).animes.filter { it.url != anime.url }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
        }

        val genres = anime.genre?.split(", ")?.map { it.trim().lowercase().replace(" ", "-") } ?: emptyList()
        val genreSearch = async {
            if (genres.isEmpty()) return@async emptyList()
            try {
                val searchUrl = "$baseUrl/browse".toHttpUrl().newBuilder().apply {
                    addQueryParameter("page", "1")
                    genres.take(2).forEach { addQueryParameter("genre[]", it) }
                }.build()
                val resp = client.newCall(GET(searchUrl, headers)).awaitSuccess()
                searchAnimeParse(resp).animes.filter { it.url != anime.url }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
        }

        (explicitRelated + listOf(titleSearch, genreSearch).awaitAll().flatten())
            .distinctBy { it.url }
    }

    // =========================== Episode List ===========================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val episodes = document.select("div.nv-info-episode-grid article.nv-info-episode-item")

        val list = episodes.map { element ->
            SEpisode.create().apply {
                val linkEl = element.selectFirst("a.nv-info-episode-main") ?: element.selectFirst("a")!!
                url = linkEl.attr("href")

                val titleEl = linkEl.selectFirst("strong")
                name = titleEl?.text() ?: linkEl.text()

                episode_number = name.substringAfter("Episode").trim().toFloatOrNull() ?: 1.0f
            }
        }
        return list.reversed()
    }

    // ============================ Video List ============================

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val buttons = document.select("button.server-video")

        val videos = buttons.parallelCatchingFlatMapBlocking { button ->
            val iframeUrl = button.attr("data-video")
            if (iframeUrl.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()

            val serverName = button.ownText().trim()
            val rawType = button.selectFirst("span")?.text() ?: ""
            val versionType = when {
                rawType.contains("Sort Sub", ignoreCase = true) -> "Soft Sub"
                rawType.contains("Hard Sub", ignoreCase = true) -> "Hard Sub"
                rawType.contains("Dub", ignoreCase = true) -> "Dub"
                else -> rawType
            }

            val subtitleTracks = mutableListOf<Track>()
            runCatching {
                val uri = Uri.parse(iframeUrl)
                val subUrl = uri.getQueryParameter("sub")
                    ?: uri.getQueryParameter("caption_1")
                    ?: uri.getQueryParameter("c1_file")
                if (!subUrl.isNullOrBlank()) {
                    val subLabel = uri.getQueryParameter("sub_1")
                        ?: uri.getQueryParameter("c1_label")
                        ?: "English"
                    subtitleTracks.add(Track(subUrl, subLabel))
                }
            }

            when {
                iframeUrl.contains("vivibebe.site") || iframeUrl.contains("vibevibe.workers.dev") || iframeUrl.contains("bibiemb.xyz") -> {
                    val iframeHtml = client.newCall(GET(iframeUrl, headers)).execute().body.string()
                    val m3u8Url = vibeRegex.find(iframeHtml)?.groupValues?.get(1)
                    if (m3u8Url != null) {
                        val finalM3u8 = if (iframeUrl.contains("bibiemb.xyz")) {
                            m3u8Url
                        } else {
                            localProxy.getProxyUrl(m3u8Url, headers)
                        }
                        playlistUtils.extractFromHls(
                            finalM3u8,
                            referer = iframeUrl,
                            videoNameGen = { quality -> "$serverName - $versionType - $quality" },
                            subtitleList = subtitleTracks,
                        )
                    } else {
                        emptyList()
                    }
                }

                iframeUrl.contains("otakuhg.site") || iframeUrl.contains("otakuvid.online") -> {
                    val extractor = VidHideExtractor(client, headers)
                    extractor.videosFromUrl(iframeUrl) { quality -> "$versionType - $quality" }.map { video ->
                        Video(
                            url = video.url,
                            quality = addServerName(serverName, video.quality),
                            videoUrl = video.videoUrl,
                            headers = video.headers,
                            subtitleTracks = video.subtitleTracks + subtitleTracks,
                        )
                    }
                }

                iframeUrl.contains("playmogo.com") || iframeUrl.contains("dood") -> {
                    val extractor = DoodExtractor(client)
                    extractor.videosFromUrl(iframeUrl, quality = versionType).map { video ->
                        Video(
                            url = video.url,
                            quality = addServerName(serverName, video.quality),
                            videoUrl = video.videoUrl,
                            headers = video.headers,
                            subtitleTracks = video.subtitleTracks + subtitleTracks,
                        )
                    }
                }

                else -> emptyList()
            }
        }

        val excludedServers = preferences.getStringSet(EXCLUDE_SERVERS_KEY, emptySet())!!
        val excludedAudios = preferences.getStringSet(EXCLUDE_AUDIO_KEY, emptySet())!!

        val preferredQuality = preferences.getString(QUALITY_KEY, QUALITY_DEFAULT)!!
        val preferredAudioType = preferences.getString(TYPE_KEY, TYPE_DEFAULT)!!
        val preferredHost = preferences.getString(HOST_KEY, HOST_DEFAULT)!!

        val filteredVideos = videos
            .filter { video ->
                val matchesServer = excludedServers.any { video.quality.contains(it, ignoreCase = true) }
                val matchesAudio = excludedAudios.any { video.quality.contains(it, ignoreCase = true) }
                !matchesServer && !matchesAudio
            }
            // Filter out bare "Video" tracks that lack a standard resolution
            .filterNot { video ->
                video.quality.contains("Video", ignoreCase = true) &&
                    QUALITY_ENTRIES.none { video.quality.contains(it, ignoreCase = true) }
            }

        val isDoodPreferred = preferredHost.equals("Doodstream", ignoreCase = true)

        fun isDoodVideo(video: Video): Boolean = video.quality.contains("Doodstream", ignoreCase = true) ||
            video.quality.contains("dood", ignoreCase = true)

        val qualitiesList = QUALITY_ENTRIES.reversed()

        return if (isDoodPreferred) {
            val (doodVideos, otherVideos) = filteredVideos.partition { isDoodVideo(it) }

            val sortedDood = doodVideos.sortedWith(
                compareByDescending<Video> { it.quality.contains(preferredAudioType, true) }
                    .thenByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it, true) } }
                    .thenByDescending { it.quality.contains(preferredHost, true) },
            )

            val sortedOthers = otherVideos.sortedWith(
                compareByDescending<Video> { it.quality.contains(preferredQuality, true) }
                    .thenByDescending { it.quality.contains(preferredAudioType, true) }
                    .thenByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it, true) } }
                    .thenByDescending { it.quality.contains(preferredHost, true) },
            )

            sortedDood + sortedOthers
        } else {
            filteredVideos.sortedWith(
                compareByDescending<Video> { it.quality.contains(preferredQuality, true) }
                    .thenByDescending { it.quality.contains(preferredAudioType, true) }
                    .thenByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it, true) } }
                    .thenByDescending { it.quality.contains(preferredHost, true) },
            )
        }
    }

    private fun addServerName(serverName: String, quality: String): String = if (serverName.isBlank() || quality.startsWith("$serverName - ", ignoreCase = true)) {
        quality
    } else {
        "$serverName - $quality"
    }

    // ============================ Preferences ===========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = TITLE_LANG_KEY,
            title = "Preferred Title Language",
            entries = TITLE_LANG_ENTRIES,
            entryValues = TITLE_LANG_ENTRIES,
            default = TITLE_LANG_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = HOST_KEY,
            title = "Preferred Host",
            entries = HOST_ENTRIES,
            entryValues = HOST_ENTRIES,
            default = HOST_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = QUALITY_KEY,
            title = "Preferred Quality",
            entries = QUALITY_ENTRIES,
            entryValues = QUALITY_ENTRIES,
            default = QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = TYPE_KEY,
            title = "Preferred Audio Type",
            entries = TYPE_ENTRIES,
            entryValues = TYPE_ENTRIES,
            default = TYPE_DEFAULT,
            summary = "%s",
        )
        screen.addSetPreference(
            key = EXCLUDE_SERVERS_KEY,
            default = emptySet(),
            title = "Exclude Host",
            summary = "Select servers to exclude from the video list",
            entries = HOST_ENTRIES,
            entryValues = HOST_ENTRIES,
        )
        screen.addSetPreference(
            key = EXCLUDE_AUDIO_KEY,
            default = emptySet(),
            title = "Exclude Audio Types",
            summary = "Select audio formats to exclude from the video list",
            entries = TYPE_ENTRIES,
            entryValues = TYPE_ENTRIES,
        )
    }

    companion object {
        const val QUALITY_KEY = "preferred_quality"
        const val QUALITY_DEFAULT = "1080p"
        val QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        const val TYPE_KEY = "preferred_type"
        const val TYPE_DEFAULT = "Soft Sub"
        val TYPE_ENTRIES = listOf("Soft Sub", "Hard Sub", "Dub")

        const val HOST_KEY = "preferred_host"
        const val HOST_DEFAULT = "HD-1"
        val HOST_ENTRIES = listOf("HD-1", "HD-2", "StreamHG", "Earnvids", "Doodstream")

        const val EXCLUDE_SERVERS_KEY = "exclude_servers"
        const val EXCLUDE_AUDIO_KEY = "exclude_audio"

        const val TITLE_LANG_KEY = "preferred_title_lang"
        const val TITLE_LANG_DEFAULT = "English"
        val TITLE_LANG_ENTRIES = listOf("English", "Romaji/Japanese")

        private val vibeRegex = Regex("""const src\s*=\s*"([^"]+)"""")
    }
}
