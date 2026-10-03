package eu.kanade.tachiyomi.animeextension.es.pandrama

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class Pandrama :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Pandrama"

    override val baseUrl = "https://www.pandrama.tv"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val apiHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept", "application/json")
            .set("Referer", "$baseUrl/")
            .build()
    }

    override fun popularAnimeRequest(page: Int) = channelRequest(page)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val result = response.parseAs<ChannelResponse>().pagination
        return AnimesPage(result.data.map { it.toSAnime() }, result.nextPage != null)
    }

    override fun latestUpdatesRequest(page: Int) = channelRequest(page, order = "updated_at:desc")

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart().orEmpty()
        return channelRequest(page, query = query, genre = genre)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun getFilterList() = AnimeFilterList(
        GenreFilter(),
    )

    private class GenreFilter :
        UriPartFilter(
            "Género",
            arrayOf(
                Pair("<Seleccionar>", ""),
                Pair("Acción", "Acción"),
                Pair("Aventura", "Aventura"),
                Pair("Boys Love", "Boys Love"),
                Pair("Ciencia ficción", "Ciencia ficción"),
                Pair("Comedia", "Comedia"),
                Pair("Crimen", "Crimen"),
                Pair("Deporte", "Deporte"),
                Pair("Drama", "Drama"),
                Pair("Escolar", "Escolar"),
                Pair("Familia", "Familia"),
                Pair("Fantasía", "Fantasía"),
                Pair("Girls Love", "Girls Love"),
                Pair("Histórico", "Histórico"),
                Pair("Juventud", "Juventud"),
                Pair("Medicina", "Medicina"),
                Pair("Melodrama", "Melodrama"),
                Pair("Misterio", "Misterio"),
                Pair("Música", "Música"),
                Pair("Romance", "Romance"),
                Pair("Sobrenatural", "Sobrenatural"),
                Pair("Suspense", "Suspense"),
                Pair("Terror", "Terror"),
                Pair("Thriller", "Thriller"),
            ),
        )

    private fun channelRequest(
        page: Int,
        order: String? = null,
        query: String = "",
        genre: String = "",
    ): Request {
        val url = "$baseUrl/api/v1/channel/dramas".toHttpUrl().newBuilder()
            .addQueryParameter("channelType", "channel")
            .addQueryParameter("returnContentOnly", "true")
            .addQueryParameter("page", page.toString())
            .apply {
                if (order != null) addQueryParameter("order", order)
                if (query.isNotBlank()) addQueryParameter("query", query)
                if (genre.isNotEmpty()) addQueryParameter("genre", genre)
            }
            .build()
        return GET(url, apiHeaders)
    }

    override fun animeDetailsRequest(anime: SAnime) = GET("$baseUrl/api/v1/titles/${anime.url.titleId()}", apiHeaders)

    override fun getAnimeUrl(anime: SAnime) = baseUrl + anime.url

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val resolved = resolveAnime(anime)
        return super.getAnimeDetails(resolved).apply {
            url = resolved.url
            title = resolved.title
        }
    }

    private suspend fun resolveAnime(anime: SAnime): SAnime {
        if (anime.url.titleIdOrNull() != null) return anime

        val title = anime.title.trim().removePrefix("🇲🇽").removePrefix("🇪🇸").trim()
        require(title.isNotBlank()) { "No se pudo migrar un drama sin título" }
        val result = client.newCall(channelRequest(1, query = title))
            .awaitSuccess()
            .parseAs<ChannelResponse>()
        return result.pagination.data.map { it.toSAnime() }
            .filter { it.title.equals(title, ignoreCase = true) }
            .distinctBy { it.url }
            .singleOrNull()
            ?: throw Exception("No se pudo migrar este drama. Búscalo de nuevo en Pandrama para actualizar su enlace.")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val result = response.parseAs<TitleResponse>()
        return result.title.toSAnime(result.credits)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val resolved = resolveAnime(anime)
        val titleId = resolved.url.titleId()
        val seasons = client.newCall(GET("$baseUrl/api/v1/titles/$titleId", apiHeaders))
            .awaitSuccess()
            .parseAs<TitleResponse>()
            .availableSeasons
            .sorted()
        val multipleSeasons = seasons.size > 1
        val episodeDateFormat = dateFormat

        return seasons
            .flatMap { fetchSeasonEpisodes(titleId, it) }
            .map { episode ->
                SEpisode.create().apply {
                    name = if (multipleSeasons) {
                        "T${episode.seasonNumber} - Episodio ${episode.episodeNumber}"
                    } else {
                        "Episodio ${episode.episodeNumber}"
                    }
                    episode_number = episode.episodeNumber.toFloat()
                    date_upload = episodeDateFormat.tryParse(episode.releaseDate?.take(DATE_LENGTH))
                    url = "${resolved.url}/season/${episode.seasonNumber}/episode/${episode.episodeNumber}"
                }
            }
            .reversed()
    }

    private suspend fun fetchSeasonEpisodes(titleId: String, season: Int): List<EpisodeDto> {
        val episodes = mutableListOf<EpisodeDto>()
        var page: Int? = 1
        while (page != null) {
            val url = "$baseUrl/api/v1/titles/$titleId/seasons/$season/episodes".toHttpUrl().newBuilder()
                .addQueryParameter("perPage", "100")
                .addQueryParameter("page", page.toString())
                .build()
            val result = client.newCall(GET(url, apiHeaders)).awaitSuccess()
                .parseAs<EpisodesResponse>()
                .pagination
            episodes += result.data
            page = result.nextPage
        }
        return episodes
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val (titleId, season, number) = EPISODE_URL_REGEX.find(episode.url)?.destructured
            ?: throw Exception("URL de episodio no válida")
        val servers = client.newCall(GET("$baseUrl/api/v1/titles/$titleId/seasons/$season/episodes/$number", apiHeaders))
            .awaitSuccess()
            .parseAs<EpisodeVideosResponse>()
            .episode
            .videos

        return servers.mapNotNull { server ->
            val url = server.src.lowercase()
            val name = when {
                url.contains("ok.ru") || url.contains("okru") -> "Okru"
                url.contains("vk.com") || url.contains("vkvideo") -> "Vk"
                else -> return@mapNotNull null
            }
            Hoster(hosterUrl = server.src, hosterName = name)
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> = serverVideoResolver(hoster.hosterUrl).sortVideos()

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedByDescending { it.hosterName.contains(server, true) }
    }

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }

    private suspend fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> okruExtractor.videosFromUrl(url)
            embedUrl.contains("vk.com") || embedUrl.contains("vkvideo") -> vkExtractor.videosFromUrl(url, "Vk:")
            else -> emptyList()
        }
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { QUALITY_REGEX.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%d"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%d"
        }.also(screen::addPreference)
    }

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private fun String.titleIdOrNull(): String? {
        val segments = baseUrl.toHttpUrl().resolve(this)?.pathSegments ?: return null
        return segments.takeIf { it.size > 1 && it[0] == "titles" }
            ?.get(1)?.takeIf { (it.toIntOrNull() ?: 0) > 0 }
    }

    private fun String.titleId() = titleIdOrNull() ?: throw Exception("URL de drama no válida")

    private val dateFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Vk"
        private val SERVER_LIST = arrayOf("Vk", "Okru")

        private const val DATE_LENGTH = 10
        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val EPISODE_URL_REGEX = Regex("""/titles/(\d+)/[^/]+/season/(\d+)/episode/(\d+)""")
    }
}
