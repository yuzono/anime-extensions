package eu.kanade.tachiyomi.animeextension.all.animeonsen

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeDetails
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeListItem
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeListResponse
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.MeilisearchResponse
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.SearchRequest
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.SearchResponse
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.VideoData
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.AniSource
import keiyoushi.source.model.Related
import keiyoushi.source.model.SAnimeUpdate
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.OkHttpClient
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

@Source
abstract class AnimeOnsen :
    AniSource(),
    ConfigurableAnimeSource {

    private val apiUrl = "https://api.animeonsen.xyz/v4"

    private val searchUrl = "https://search.animeonsen.xyz"

    override val supportsLatest = false

    override fun OkHttpClient.Builder.configureClient() = this
        .addInterceptor(AOAPIInterceptor(network.client, apiUrl, baseUrl))
        .addInterceptor(SearchInterceptor(network.client, baseUrl, searchUrl))

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", AO_USER_AGENT)
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-site")

    private val preferredTitle: String
        get() = preferences.getString(PREF_TITLE_KEY, PREF_TITLE_DEFAULT)!!

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage = getIndexPage(page)

    private suspend fun getIndexPage(page: Int): AnimesPage {
        val start = (page - 1) * 30
        val response = client.get("$apiUrl/content/index?start=$start&limit=30").parseAs<AnimeListResponse>()
        return AnimesPage(response.content.map { it.toSAnime() }, response.hasNextPage)
    }

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = AnimeOnsenFilters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val hits = client.post("$searchUrl/indexes/content/search", SearchRequest(query).toJsonRequestBody())
                .parseAs<MeilisearchResponse>().hits
            return AnimesPage(hits.map { it.toSAnime() }, false)
        }

        val genre = filters.firstInstanceOrNull<AnimeOnsenFilters.GenreFilter>()?.getValue()
        if (genre.isNullOrBlank()) return getIndexPage(page)

        val results = client.get("$apiUrl/content/index/genre/$genre").parseAs<SearchResponse>().result
        return AnimesPage(results.map { it.toSAnime() }, false)
    }

    // =========================== Anime Details ============================
    override fun getAnimeUrl(anime: SAnime) = "$baseUrl/details/${anime.url}"

    override suspend fun fetchAnimeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeUpdate {
        if (!fetchDetails && !fetchEpisodes) return SAnimeUpdate(anime, episodes)

        return coroutineScope {
            val detailsDeferred = if (fetchDetails) async { fetchDetails(anime.url) } else null
            val episodeMapDeferred = async {
                if (fetchEpisodes) fetchEpisodeMap(anime.url) else runCatchingCancellable { fetchEpisodeMap(anime.url) }
            }

            val episodeMap = episodeMapDeferred.await()
            val details = detailsDeferred?.await()

            SAnimeUpdate(
                anime = details?.let { toSAnimeWithDescription(it, episodeMap) } ?: anime,
                episodes = if (fetchEpisodes) episodeMap!!.toEpisodes(anime.url) else episodes,
            )
        }
    }

    private suspend fun fetchDetails(contentId: String) = client.get("$apiUrl/content/$contentId/extensive").parseAs<AnimeDetails>()

    private suspend fun toSAnimeWithDescription(details: AnimeDetails, episodeMap: Map<String, EpisodeDto>?): SAnime {
        val subtitles = episodeMap?.keys?.firstOrNull()?.let { fetchSubtitleLanguages(details.contentId, it) }.orEmpty()

        return SAnime.create().apply {
            url = details.contentId
            title = details.title()
            status = parseStatus(details.malData?.status)
            author = details.malData?.studios?.joinToString { it.name }
            genre = details.malData?.genres?.joinToString { it.name }
            thumbnail_url = thumbnailUrl(details.contentId)
            description = buildString {
                details.malData?.meanScore?.let { score ->
                    val starCount = (score / 2.0).roundToInt().coerceIn(0, 5)
                    append("★".repeat(starCount), "☆".repeat(5 - starCount), " ", score, "\n\n")
                }
                details.malData?.synopsis?.let(::append)

                val extras = buildList {
                    details.malData?.rating?.let { add("**Rating:** ${it.replace("_", " ").uppercase()}") }
                    if (subtitles.isNotEmpty()) add("**Subtitles:** ${subtitles.joinToString()}")
                    details.malId?.let { add("[MAL](https://myanimelist.net/anime/$it)") }
                }
                if (extras.isNotEmpty()) append("\n\n", extras.joinToString("\n"))
            }.trimEnd()
        }
    }

    private suspend fun fetchSubtitleLanguages(contentId: String, episodeNumber: String): Collection<String> = runCatchingCancellable {
        client.get("$apiUrl/content/$contentId/video/$episodeNumber").parseAs<VideoData>().metadata.subtitles.values
    } ?: emptyList()

    // ============================== Episodes ==============================
    private suspend fun fetchEpisodeMap(contentId: String) = client.get("$apiUrl/content/$contentId/episodes").parseAs<Map<String, EpisodeDto>>()

    private fun Map<String, EpisodeDto>.toEpisodes(contentId: String) = map { (epNum, item) ->
        SEpisode.create().apply {
            url = "$contentId/video/$epNum"
            episode_number = epNum.toFloatOrNull() ?: -1f
            name = when (preferredTitle) {
                "english" -> "Episode $epNum: ${item.nameEn ?: item.nameJp ?: ""}"
                else -> "Episode $epNum: ${item.nameJp ?: item.nameEn ?: ""}"
            }
        }
    }.sortedByDescending { it.episode_number }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val contentId = episode.url.substringBefore("/video/")
        val episodeNumber = episode.url.substringAfter("/video/")
        return "$baseUrl/watch/$contentId?episode=$episodeNumber"
    }

    // ============================ Video Links =============================
    override suspend fun fetchHosterList(episode: SEpisode): List<Hoster> {
        val videoData = client.get("$apiUrl/content/${episode.url}").parseAs<VideoData>()
        val subtitleLangs = videoData.metadata.subtitles

        val subs = videoData.uri.subtitles.sortSubs().mapNotNull { (langPrefix, subUrl) ->
            val language = subtitleLangs[langPrefix] ?: return@mapNotNull null
            Track(subUrl, language)
        }

        return listOf(
            Video(videoUrl = videoData.uri.stream, videoTitle = "Default (720p)", headers = headers, subtitleTracks = subs),
        ).toHosterList()
    }

    // ========================== Related Anime =============================
    override val hasRelatedAnime = true

    override suspend fun fetchRelatedAnime(anime: SAnime): List<Related> {
        val details = fetchDetails(anime.url)
        val ids = listOfNotNull(details.previousSeason, details.nextSeason).filter { it.isNotEmpty() }
        if (ids.isEmpty()) return emptyList()

        // A season that fails to load doesn't hide the other
        val seasons = coroutineScope {
            ids.map { id -> async { runCatchingCancellable { fetchDetails(id) } } }.awaitAll()
        }.filterNotNull().map { season ->
            SAnime.create().apply {
                url = season.contentId
                title = season.title()
                thumbnail_url = thumbnailUrl(season.contentId)
            }
        }

        return listOf(Related("Seasons", seasons))
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_TITLE_KEY
            title = PREF_TITLE_TITLE
            entries = PREF_TITLE_ENTRIES
            entryValues = PREF_TITLE_VALUES
            setDefaultValue(PREF_TITLE_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_ENTRIES
            entryValues = PREF_SUB_VALUES
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun parseStatus(statusString: String?): Int = when (statusString?.trim()) {
        "finished_airing" -> SAnime.COMPLETED
        else -> SAnime.ONGOING
    }

    private fun thumbnailUrl(contentId: String) = "$apiUrl/image/210x300/$contentId"

    private fun AnimeDetails.title() = when (preferredTitle) {
        "english" -> contentTitleEn ?: contentTitle!!
        else -> contentTitle ?: contentTitleEn!!
    }

    private fun AnimeListItem.toSAnime() = SAnime.create().apply {
        url = contentId
        title = when (preferredTitle) {
            "english" -> contentTitleEn ?: contentTitle ?: contentTitleJp!!
            else -> contentTitle ?: contentTitleJp ?: contentTitleEn!!
        }
        thumbnail_url = thumbnail ?: contentImage ?: thumbnailUrl(contentId)
    }

    private suspend inline fun <T> runCatchingCancellable(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun Map<String, String>.sortSubs(): List<Map.Entry<String, String>> {
        val sub = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!

        return entries.sortedWith(
            compareBy { it.key.contains(sub) },
        ).reversed()
    }

    companion object {
        const val AO_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.3"

        // Title and episode name preferences
        private const val PREF_TITLE_KEY = "preferred_title"
        private const val PREF_TITLE_TITLE = "Preferred Title Language"
        private const val PREF_TITLE_DEFAULT = "romaji"
        private val PREF_TITLE_ENTRIES = arrayOf("Romaji", "English")
        private val PREF_TITLE_VALUES = arrayOf("romaji", "english")

        // Subtitle preferences
        private const val PREF_SUB_KEY = "preferred_subLang"
        private const val PREF_SUB_TITLE = "Preferred sub language"
        const val PREF_SUB_DEFAULT = "en-US"
        private val PREF_SUB_ENTRIES = arrayOf(
            "العربية", "Deutsch", "English", "Español (Spain)",
            "Español (Latin)", "Français", "Italiano",
            "Português (Brasil)", "Русский",
        )
        private val PREF_SUB_VALUES = arrayOf(
            "ar-ME", "de-DE", "en-US", "es-ES",
            "es-LA", "fr-FR", "it-IT",
            "pt-BR", "ru-RU",
        )
    }
}
