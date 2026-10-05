package eu.kanade.tachiyomi.animeextension.pt.animefire

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AFAnime
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AFDetails
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AFEpisode
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AFHome
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AFPlayback
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AFResponse
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.network.get
import keiyoushi.utils.bodyString
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AnimeFire :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "Anime Fire"
    override val baseUrl = "https://animefire.one"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val apiUrl = "https://api.animefire.one"
    private val preferences by getPreferencesLazy()
    private val streamServer by lazy { AnimeFireStreamServer(client, headers) }

    override fun headersBuilder() = super
        .headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.7")

    private fun apiEndpoint(path: String) = "$apiUrl$path"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("v", "3")

    override fun popularAnimeRequest(page: Int) = GET(
        apiEndpoint("/animes").addQueryParameter("page", page.toString()).build(),
        headers,
    )

    override fun popularAnimeParse(response: Response): AnimesPage {
        val result = response.parseAs<AFResponse<List<AFAnime>>>()
        val meta = result.meta
        return AnimesPage(result.data.map { it.toSAnime() }, meta != null && meta.currentPage < meta.lastPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET(apiEndpoint("/home").build(), headers)

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val result = client.get(latestUpdatesRequest(page).url).parseAs<AFResponse<AFHome>>()
        val episodes =
            result.data.carousels
                .firstOrNull { it.key == "new-episodes" }
                ?.items
                .orEmpty()
                .distinctBy { it.id }
        val offset = (page - 1) * LATEST_PAGE_SIZE
        // Recent cards contain episode IDs; paginate their distinct parents in first-seen order.
        val animes = linkedMapOf<String, SAnime>()
        for (batch in episodes.chunked(3)) {
            val parents = batch.parallelMap { episode ->
                client.get(apiEndpoint("/episode").addPathSegment(episode.id).build())
                    .parseAs<AFResponse<AFPlayback>>()
                    .data.anime
            }
            parents.forEach { anime -> animes.getOrPut(anime.id) { anime.toSAnime() } }
            // Resolve one extra parent to determine whether another page exists.
            if (animes.size > offset + LATEST_PAGE_SIZE) break
        }
        return AnimesPage(animes.values.drop(offset).take(LATEST_PAGE_SIZE), animes.size > offset + LATEST_PAGE_SIZE)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            require(url.host == baseUrl.toHttpUrl().host || url.host in LEGACY_HOSTS) { "URL não suportada" }
            val path = url.pathSegments
            return when (path.firstOrNull()) {
                "anime" -> getSearchAnime(page, "$PREFIX_SEARCH${path.getOrNull(1).orEmpty()}", filters)
                "animes" -> super.getSearchAnime(page, legacySearch(path.getOrNull(1).orEmpty()), filters)
                else -> throw Exception("URL não suportada")
            }
        }
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            if (id.endsWith(LEGACY_SUFFIX)) {
                return super.getSearchAnime(page, legacySearch(id), filters)
            }
            require(id.isNotBlank() && '/' !in id) { "ID não suportado" }
            val anime =
                client
                    .get(apiEndpoint("/anime").addPathSegment(id).build())
                    .parseAs<AFResponse<AFDetails>>()
                    .data.hero
                    .toSAnime(details = true)
            return AnimesPage(listOf(anime), false)
        }
        return super.getSearchAnime(page, query, filters)
    }

    private fun legacySearch(slug: String): String = slug
        .removeSuffix(LEGACY_SUFFIX)
        .removeSuffix("-dublado")
        .replace('-', ' ')

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ) = GET(
        apiEndpoint(
            when {
                query.isNotBlank() -> "/animes/pesquisar"
                filters.firstInstanceOrNull<AFFilters.FormatFilter>()?.state == 1 -> "/animes/filmes"
                else -> "/animes"
            },
        ).apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) {
                addQueryParameter("q", query.trim())
            } else {
                filters
                    .firstInstanceOrNull<AFFilters.GenreFilter>()
                    ?.value()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { addQueryParameter("genre", it) }
                filters
                    .firstInstanceOrNull<AFFilters.AudioFilter>()
                    ?.value()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { addQueryParameter("audio", it) }
            }
        }.build(),
        headers,
    )

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    private fun animeApiUrl(anime: SAnime): HttpUrl {
        val url = getAnimeUrl(anime).toHttpUrl()
        require(url.pathSegments.firstOrNull() == "anime" && !url.pathSegments.getOrNull(1).isNullOrBlank()) {
            "O site mudou os links dos animes. Migre este anime para a nova versão do Anime Fire."
        }
        return apiEndpoint("/anime")
            .addPathSegment(url.pathSegments[1])
            .apply {
                url.queryParameter("season")?.let { addQueryParameter("season", it) }
            }.build()
    }

    override fun animeDetailsRequest(anime: SAnime) = GET(animeApiUrl(anime), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val details = response.parseAs<AFResponse<AFDetails>>().data
        return details.hero.toSAnime(details = true).apply {
            response.request.url.queryParameter("season")?.let {
                url += "?season=$it"
                title += " - Temporada $it"
            }
        }
    }

    private fun AFAnime.toSAnime(details: Boolean = false) = SAnime.create().apply {
        url = "/anime/$id"
        title = listOf("BR", "US", "JP").firstNotNullOfOrNull { titles[it]?.takeIf(String::isNotBlank) }
            ?: titles.values.firstOrNull { it.isNotBlank() } ?: id
        thumbnail_url = posterSrc
        status =
            when (this@toSAnime.status) {
                "completed" -> SAnime.COMPLETED
                "airing" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        if (details) {
            description =
                buildString {
                    synopsis?.let { append(it) }
                    audio?.let { append("\n\nÁudio: $it") }
                    publishedAt?.let { append("\nLançamento: $it") }
                }
            genre = genres.joinToString()
            initialized = true
        }
    }

    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val details = response.parseAs<AFResponse<AFDetails>>().data
        val selectedSeason =
            response.request.url
                .queryParameter("season")
                ?.toIntOrNull()
        val firstNumbers = details.seasons.associate { it.number to it.firstEpisodeNumber }
        val dateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        return details.episodes
            .filter { selectedSeason == null || it.season == selectedSeason }
            .sortedWith(compareByDescending<AFEpisode> { it.season ?: 0 }.thenByDescending { it.number })
            .map { episode ->
                SEpisode.create().apply {
                    url = "/anime/${details.hero.id}?episode=${episode.id}"
                    name =
                        buildString {
                            episode.season?.let { append("T$it ") }
                            append("EP. ${episode.number.toInt()}")
                            episode.title?.takeIf { it.isNotBlank() }?.let { append(" - $it") }
                        }
                    episode_number =
                        if (selectedSeason == null) {
                            episode.number + (firstNumbers[episode.season] ?: 1) - 1
                        } else {
                            episode.number
                        }
                    date_upload = dateFormat.tryParse(episode.createdAt)
                }
            }
    }

    override fun seasonListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun seasonListParse(response: Response): List<SAnime> {
        val details = response.parseAs<AFResponse<AFDetails>>().data
        if (details.seasons.size <= 1) return emptyList()
        return details.seasons.map { season ->
            details.hero.toSAnime(details = true).apply {
                url += "?season=${season.number}"
                title += " - Temporada ${season.number}"
                season.title?.takeIf { it.isNotBlank() }?.let { title += ": $it" }
            }
        }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val details = response.parseAs<AFResponse<AFDetails>>().data
        return (details.relations?.items.orEmpty() + details.recommendations?.items.orEmpty())
            .filter { it.id != details.hero.id }
            .distinctBy { it.id }
            .map { it.toSAnime() }
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val id =
            getEpisodeUrl(episode).toHttpUrl().queryParameter("episode")
                ?: throw Exception("Atualize a lista de episódios ou migre este anime para o novo site.")
        return client
            .get(apiEndpoint("/episode").addPathSegment(id).build(), CacheControl.FORCE_NETWORK)
            .use(::hosterListParse)
            .sortHosters()
    }

    override fun hosterListParse(response: Response): List<Hoster> = response
        .parseAs<AFResponse<AFPlayback>>()
        .data.streams
        .filter { !it.isOffline && !it.url.isNullOrBlank() }
        .map { stream ->
            Hoster(
                hosterUrl = stream.url!!,
                hosterName =
                buildString {
                    append(if (stream.audio == "dublado") "Dublado" else "Legendado")
                    if (stream.isMtl) append(" (tradução automática)")
                },
            )
        }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val masterUrl = hoster.hosterUrl.toHttpUrl()
        val lines = client.get(masterUrl, headers).bodyString().lines()
        val videos = mutableListOf<Video>()
        var info: String? = null
        for (line in lines.map { it.trim() }) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> info = line
                line.isEmpty() || line.startsWith("#") -> Unit
                info != null -> {
                    val quality = RESOLUTION_REGEX.group(info)?.let { "${it}p" } ?: "Auto"
                    val codec = CODECS_REGEX.group(info)?.let(::codecLabel)
                    info = null
                    videos += Video(
                        videoUrl = masterUrl.resolve(line)?.toString() ?: continue,
                        videoTitle = buildString {
                            append(hoster.hosterName).append(" - ").append(quality)
                            codec?.let { append(" (").append(it).append(")") }
                        },
                    )
                }
            }
        }
        return videos.ifEmpty { listOf(Video(videoUrl = hoster.hosterUrl, videoTitle = hoster.hosterName)) }.sortVideos()
    }

    private fun Regex.group(input: String) = find(input)?.groupValues?.get(1)

    private fun codecLabel(codecs: String) = codecs.split(',').firstNotNullOfOrNull { codec ->
        when {
            codec.startsWith("av01") -> "AV1"
            codec.startsWith("avc1") -> "H.264"
            codec.startsWith("hvc1") || codec.startsWith("hev1") -> "HEVC"
            codec.startsWith("vp09") -> "VP9"
            else -> null
        }
    }

    override suspend fun resolveVideo(video: Video): Video = video.copy(
        videoUrl = streamServer.localUrl(video.videoUrl),
        initialized = true,
    )

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val audio = preferences.getString(PREF_AUDIO_KEY, "Legendado")!!
        return sortedByDescending { it.hosterName.startsWith(audio) }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, "720p")!!
        return map { it.copy(preferred = it.videoTitle.contains(quality)) }.sortedByDescending { it.preferred }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context)
            .apply {
                key = PREF_QUALITY_KEY
                title = "Qualidade preferida"
                entries = arrayOf("360p", "480p", "720p", "1080p")
                entryValues = entries
                setDefaultValue("720p")
                summary = "%s"
            }.also(screen::addPreference)
        ListPreference(screen.context)
            .apply {
                key = PREF_AUDIO_KEY
                title = "Áudio preferido"
                entries = arrayOf("Legendado", "Dublado")
                entryValues = entries
                setDefaultValue("Legendado")
                summary = "%s"
            }.also(screen::addPreference)
    }

    override fun getFilterList() = AFFilters.filterList

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val LEGACY_SUFFIX = "-todos-os-episodios"
        private val LEGACY_HOSTS = setOf("animefire.io", "animefire.plus")
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val LATEST_PAGE_SIZE = 9
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")
        private val CODECS_REGEX = Regex("""CODECS="([^"]+)"""")
    }
}
