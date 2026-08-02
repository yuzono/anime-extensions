package eu.kanade.tachiyomi.animeextension.ru.anilibria

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class Anilibria :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniLibria"
    override val lang = "ru"
    override val baseUrl = "https://api.anilibria.tv"
    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Anilibria/3.0 (Android)")
                .build()
            chain.proceed(request)
        }
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US) }

    private val preferences by getPreferencesLazy()
        Injekt.get<Application>(),
        "source_$id",
    )

    // ========================
    //  DTO
    // ========================

    @Serializable
    data class ApiResponse<T>(val data: T)

    @Serializable
    data class TitleDto(
        val id: Int = 0,
        val code: String = "",
        val names: NamesDto = NamesDto(),
        val posters: PostersDto = PostersDto(),
        val description: String = "",
        val season: SeasonDto = SeasonDto(),
        val type: TypeDto = TypeDto(),
        val status: StatusDto = StatusDto(),
        val genres: List<String> = emptyList(),
        val team: TeamDto = TeamDto(),
        val player: PlayerDto = PlayerDto(),
    )

    @Serializable data class NamesDto(val ru: String = "", val en: String = "")

    @Serializable data class PostersDto(
        val small: PosterUrlDto = PosterUrlDto(),
        val medium: PosterUrlDto = PosterUrlDto(),
        val original: PosterUrlDto = PosterUrlDto(),
    )

    @Serializable data class PosterUrlDto(val url: String = "")

    @Serializable data class SeasonDto(val year: Int = 0, val string: String = "")

    @Serializable data class TypeDto(val full_string: String = "", val string: String = "")

    @Serializable data class StatusDto(val string: String = "")

    @Serializable data class TeamDto(val voice: List<String> = emptyList(), val translator: List<String> = emptyList())

    @Serializable data class PlayerDto(val host: String = "", val episodes: PlayerEpisodesDto = PlayerEpisodesDto())

    @Serializable data class PlayerEpisodesDto(val first: Int = 0, val last: Int = 0)

    // ========================
    //  Популярное / Каталог
    // ========================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/v3/title/updates".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "20")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("filter", "id,code,names,posters,description,season,type,status,genres")
            .build()
        return GET(url.toString(), headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val result = response.parseAs<ApiResponse<List<TitleDto>>>()
        val titles = result.data.map { it.toSAnime() }
        val hasNext = result.data.size >= 20
        return AnimesPage(titles, hasNext)
    }

    // ========================
    //  Поиск
    // ========================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/v3/title/search".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("limit", "20")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("filter", "id,code,names,posters,description,season,type,status,genres")
            .build()
        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ========================
    //  Новинки
    // ========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/v3/title/changes".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "20")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("filter", "id,code,names,posters,description,season,type,status,genres")
            .build()
        return GET(url.toString(), headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ========================
    //  Детали аниме
    // ========================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/v3/title?code=${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val result = response.parseAs<ApiResponse<TitleDto>>()
        return result.data.toSAnimeFull()
    }

    // ========================
    //  Список эпизодов
    // ========================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl/v3/title?code=${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val result = response.parseAs<ApiResponse<TitleDto>>()
        val title = result.data
        val code = title.code
        val first = title.player.episodes.first
        val last = title.player.episodes.last
        val episodes = mutableListOf<SEpisode>()

        for (i in first..last) {
            episodes.add(
                SEpisode.create().apply {
                    url = "$code/$i" // сохраняем code и номер серии
                    name = "Серия $i"
                    episode_number = i.toFloat()
                },
            )
        }
        return episodes.reversed()
    }

    // ========================
    //  Видео
    // ========================

    override fun videoListRequest(episode: SEpisode): Request {
        // episode.url = "code/номер" — вытаскиваем code и запрашиваем детали
        val parts = episode.url.split("/")
        val code = parts.firstOrNull() ?: episode.url
        val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
        return GET("$baseUrl/v3/title?code=$code&playlist_type=array", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val result = response.parseAs<ApiResponse<TitleDto>>()
        val title = result.data
        val host = title.player.host
        val code = title.code

        // Получаем номер эпизода из URL запроса
        val requestUrl = response.request.url.toString()
        val epNum = requestUrl.substringAfter("code=").substringBefore("&")
            .let { code -> code.toIntOrNull() ?: 1 }

        // Собираем HLS ссылки в разных качествах
        val qualities = listOf("1080p", "720p", "480p", "360p")
        return qualities.mapNotNull { quality ->
            val videoUrl = "https://$host/${code}_Episode_${epNum}_[$quality].mp4/index.m3u8"
            Video(videoUrl, quality, videoUrl)
        }
    }

    // ========================
    //  Фильтры
    // ========================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Фильтры скоро появятся"),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Сейчас работает поиск по названию"),
    )

    // ========================
    //  Настройки
    // ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            EditTextPreference(screen.context).apply {
                key = "preferred_quality"
                title = "Качество видео"
                summary = "1080p, 720p, 480p или 360p"
                setDefaultValue("1080p")
                dialogTitle = "Предпочитаемое качество"
            },
        )
    }

    // ========================
    //  Helpers
    // ========================

    private fun TitleDto.toSAnime(): SAnime = SAnime.create().apply {
        url = code
        title = names.ru.ifEmpty { names.en.ifEmpty { code } }
        thumbnail_url = "https://anilibria.tv${posters.medium.url}"
        genre = genres.joinToString(", ")
        status = parseStatus(this@toSAnime.status.`string`)
    }

    private fun TitleDto.toSAnimeFull(): SAnime = toSAnime().apply {
        description = this@toSAnimeFull.description
        author = team.voice.joinToString(", ")
        artist = team.translator.joinToString(", ")
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("выходит", ignoreCase = true) ||
            status.contains("ongoing", ignoreCase = true) -> SAnime.ONGOING

        status.contains("заверш", ignoreCase = true) ||
            status.contains("released", ignoreCase = true) -> SAnime.COMPLETED

        else -> SAnime.UNKNOWN
    }
}
