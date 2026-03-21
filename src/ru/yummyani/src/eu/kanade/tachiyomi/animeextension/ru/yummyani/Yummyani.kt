package eu.kanade.tachiyomi.animeextension.ru.yummyani

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class Yummyani :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Yummyani"

    override val lang = "ru"

    override val supportsLatest = true

    override val baseUrl = "https://yummyanime.com"
    private val apiUrl = "https://api.yani.tv"
    private val applicationToken = "yummyanime_app_token" // В реальном проекте нужно получить токен

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val dateFormatter by lazy { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH) }

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("360", "480", "720", "1080")

        private const val PREF_USE_MAX_QUALITY_KEY = "pref_use_max_quality"
        private const val PREF_USE_MAX_QUALITY_DEFAULT = true

        private const val PREF_DUB_TEAM_KEY = "pref_dub_team"
    }

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val requestToProceed = request.newBuilder()
                .header("X-Application", applicationToken)
                .header("Lang", "ru")
                .build()

            chain.proceed(requestToProceed)
        }.build()

    // =============================== Preference ===============================
    private val preferences by getPreferencesLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_MAX_QUALITY_KEY
            title = "Использовать максимальное доступное качество"
            summary = "Для каждой студии озвучки будет выбрано максимальное качество"
            setDefaultValue(PREF_USE_MAX_QUALITY_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }

    // =============================== Details ===============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val animeId = anime.url.toIntOrNull() ?: return GET("$apiUrl/feed")
        val url = "$apiUrl/anime/$animeId".toHttpUrl()

        return GET(url)
    }

    override fun getAnimeUrl(anime: SAnime) = "$baseUrl/catalog/item/${anime.url}"

    override fun animeDetailsParse(response: Response): SAnime {
        val animeData = response.parseAs<AnimeData>()

        return SAnime.create().apply {
            url = animeData.id.toString()
            title = animeData.rusName ?: animeData.name ?: "Без названия"
            thumbnail_url = animeData.poster?.url
            description = animeData.description
            status = convertStatus(animeData.status?.id)
            genre = animeData.genres?.joinToString { it.rusName ?: it.name ?: "" }?.trim()
            author = animeData.kind
        }
    }

    // =============================== Episodes ===============================
    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.toIntOrNull() ?: return GET("$apiUrl/feed")
        val url = "$apiUrl/anime/$animeId/videos".toHttpUrl()

        return GET(url)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val videosResponse = response.parseAs<VideosResponse>()

        return videosResponse.items.map { videoInfo ->
            SEpisode.create().apply {
                url = videoInfo.id.toString()
                name = videoInfo.title ?: "Серия ${videoInfo.episodeNumber ?: 0}"
                episode_number = (videoInfo.episodeNumber ?: 0).toFloat()
                date_upload = parseDate(videoInfo.createdAt)
            }
        }.reversed()
    }

    // =============================== Video List ===============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoId = episode.url.toIntOrNull() ?: return emptyList()
        val url = "$apiUrl/video/$videoId".toHttpUrl()

        return try {
            client.newCall(GET(url))
                .awaitSuccess()
                .use { response ->
                    // Для Yummyani нужно парсить страницу с видео или использовать API для получения ссылок
                    // В данном случае используем упрощенную реализацию
                    val videoData = response.parseAs<VideoInfo>()
                    parseVideoLinks(videoData)
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val videoId = episode.url.toIntOrNull() ?: return GET("$apiUrl/feed")
        return GET("$apiUrl/video/$videoId".toHttpUrl())
    }

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val limit = 20
        val offset = (page - 1) * limit
        val url = "$apiUrl/anime".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("order_by", "created_at")
            .addQueryParameter("order_type", "desc")
            .build()

        return GET(url)
    }

    // =============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeList = response.parseAs<AnimeListResponse>()

        val animes = animeList.items.map { it.toSAnime() }
        val hasNext = animeList.total > animeList.offset + animeList.limit

        return AnimesPage(animes, hasNext)
    }

    override fun popularAnimeRequest(page: Int): Request {
        val limit = 20
        val offset = (page - 1) * limit
        val url = "$apiUrl/anime".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("order_by", "rating")
            .addQueryParameter("order_type", "desc")
            .build()

        return GET(url)
    }

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val limit = 20
        val offset = (page - 1) * limit
        val urlBuilder = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())

        // Добавляем фильтры
        val searchParams = YummyaniFilters.getSearchParameters(filters)
        searchParams.genres.forEach { urlBuilder.addQueryParameter("genres[]", it) }
        searchParams.kind?.let { urlBuilder.addQueryParameter("kind", it) }
        searchParams.status?.let { urlBuilder.addQueryParameter("status", it) }
        searchParams.season?.let { urlBuilder.addQueryParameter("season", it) }
        searchParams.rating?.let { urlBuilder.addQueryParameter("rating", it.toString()) }

        return GET(urlBuilder.build())
    }

    override fun getFilterList() = YummyaniFilters.FILTER_LIST

    // =============================== Utils ===============================
    private fun parseVideoLinks(videoInfo: VideoInfo): List<Video> {
        val useMaxQuality = preferences.getBoolean(
            PREF_USE_MAX_QUALITY_KEY,
            PREF_USE_MAX_QUALITY_DEFAULT,
        )

        // Здесь должна быть логика извлечения ссылок на видео из Yummyani
        // Для примера создадим заглушку
        val quality = if (useMaxQuality) "1080" else "720"
        val videoUrl = videoInfo.url ?: return emptyList()

        return listOf(
            Video(
                url = videoUrl,
                quality = "Yummyani ($quality p)",
                videoUrl = videoUrl,
            ),
        )
    }

    private fun parseDate(dateString: String?): Long {
        if (dateString == null) return 0L
        return try {
            dateFormatter.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun convertStatus(statusId: Int?): Int = when (statusId) {
        1 -> SAnime.ONGOING // Онгоинг
        2 -> SAnime.COMPLETED // Вышел
        3 -> SAnime.ONGOING // Анонс
        else -> SAnime.UNKNOWN
    }

    // =============================== Converters ===============================
    private fun AnimeData.toSAnime() = SAnime.create().apply {
        url = this@toSAnime.id.toString()
        title = rusName ?: name ?: "Без названия"
        thumbnail_url = poster?.url
        description = description
        status = convertStatus(this@toSAnime.status?.id)
        genre = genres?.joinToString { it.rusName ?: it.name ?: "" }?.trim()
        author = kind
    }
}
