package eu.kanade.tachiyomi.animeextension.pt.tomato

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.AnimeResultDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.EpisodeInfoDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.EpisodesResultDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.FeedItemDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.FeedResponseDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.SearchAnimeItemDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.SearchResultDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class Tomato :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Tomato"

    override val baseUrl = "https://prod-api.tomatoanimes.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    override fun headersBuilder() = super.headersBuilder().apply {
        set("Accept", "application/json, text/plain, */*")
        set("Accept-Encoding", "gzip, deflate")
        set("Authorization", "Bearer $TOKEN")
        set("User-Agent", "okhttp/4.11.0")
        set("x-app", "1.4.3")
    }

    private var randomIp: String = ""

    override val client by lazy {
        network.client.newBuilder()
            .rateLimit(5, 1.seconds)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                request.apply {
                    addHeader("request-time", System.currentTimeMillis().toString())
                    removeHeader("Cache-Control")
                    if (chain.request().url.toString().endsWith("/stream")) {
                        header("User-Agent", "tomato-android")
                    }
                }
                chain.proceed(request.build())
            }
            .addInterceptor { chain ->
                var response = chain.proceed(chain.request())
                val contentEncoding = response.header("Content-Encoding")

                if (contentEncoding == "gzip") {
                    response = runCatching {
                        val body = response.body
                        val parsedBody = body.byteStream().let { gzipInputStream ->
                            GZIPInputStream(gzipInputStream).use { inputStream ->
                                val outputStream = ByteArrayOutputStream()
                                inputStream.copyTo(outputStream)
                                outputStream.toByteArray()
                            }
                        }
                        response.createNewWithCompatBody(parsedBody, body.contentType())
                    }.getOrElse { response }
                }

                response
            }
            .addInterceptor { chain ->
                val maxRetries = 2
                var attempt = 0
                var response: Response? = null
                var lastException: IOException? = null

                while (attempt < maxRetries) {
                    try {
                        response?.close()
                        val request = chain.request().newBuilder()
                        if (randomIp.isNotBlank()) {
                            request.addHeader("X-Forwarded-For", randomIp)
                        }
                        val currentResponse = chain.proceed(request.build())
                        response = currentResponse

                        if (currentResponse.code != 500) {
                            return@addInterceptor currentResponse
                        }
                    } catch (e: IOException) {
                        lastException = e
                    }

                    randomIp = generateRandomIp()
                    attempt++
                }

                // if still error after retries
                response?.close()
                lastException?.let { throw it }
                throw IOException("Max retries reached for request: ${chain.request().url}")
            }
            .build()
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = feedRequest()

    override fun popularAnimeParse(response: Response): AnimesPage {
        val items = response.parseFeedSection(FEED_TYPE_POPULAR)
        val animes = items.mapNotNull { it.toPopularAnime() }
        return AnimesPage(animes, false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = feedRequest()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val items = response.parseFeedSection(FEED_TYPE_LATEST)
        val animes = items.mapNotNull { it.toLatestAnime() }
        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = TomatoFilters.getSearchParameters(filters)

        val data = buildJsonObject {
            put("token", TOKEN)
            put("search", query)
            put("content_type", "anime")
            put("page", page - 1)

            if (params.genres.isNotEmpty()) {
                putJsonArray("tags") {
                    params.genres.forEach { add(it) }
                }
            }
        }

        val body = json.encodeToString(JsonObject.serializer(), data)
            .toRequestBody("application/json".toMediaType())

        return POST("$baseUrl/v2/content/search", headers = headers, body = body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchResult = response.parseAs<SearchResultDto>().result
        val results = searchResult.map { it.toSAnime() }
        return AnimesPage(results, false)
    }

    private fun SearchAnimeItemDto.toSAnime(): SAnime = SAnime.create().apply {
        setUrlWithoutDomain("$baseUrl/v2/anime/$id")
        title = name
        thumbnail_url = image
    }

    override fun getFilterList() = TomatoFilters.FILTER_LIST

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val anime = response.parseAs<AnimeResultDto>()
        return SAnime.create().apply {
            setUrlWithoutDomain("$baseUrl/v2/anime/${anime.animeDetails.animeId}")
            title = anime.animeDetails.animeName
            description = anime.animeDetails.animeDescription
            genre = anime.animeDetails.animeGenre
            thumbnail_url = anime.animeDetails.animeCoverUrl
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val anime = response.parseAs<AnimeResultDto>()

        val seasons = anime.animeSeasons

        val episodeList = mutableListOf<SEpisode>()

        seasons.forEach { season ->
            var nextPage = 0
            do {
                val data = buildJsonObject {
                    put("token", TOKEN)
                    put("page", nextPage)
                    put("order", "ASC")
                }

                val body = json.encodeToString(JsonObject.serializer(), data)
                    .toRequestBody("application/json".toMediaType())

                val request = POST(
                    "$baseUrl/season/${season.seasonId}/episodes",
                    headers = headers,
                    body = body,
                )
                val episodes =
                    client.newCall(request).execute().parseAs<EpisodesResultDto>().data

                episodes.forEach { episode ->
                    val partName = "Temporada ${season.seasonNumber} x ${episode.epNumber}"
                    val fullName = "$partName - ${episode.epName}"

                    val prev = episodeList.find { it.name.contains(partName) }

                    val newUrl = "&episode[${season.seasonDubbed}]=${episode.epId}"
                    if (prev != null) {
                        prev.url += newUrl
                        // Update scanlator with legendado/dublado info
                        prev.scanlator = updateScanlatorInfo(prev.scanlator, season.seasonDubbed)
                    } else {
                        episodeList.add(
                            SEpisode.create().apply {
                                episode_number = episode.epNumber
                                name = fullName
                                url = "http://localhost?season=${season.seasonNumber}$newUrl"
                                scanlator = getScanlatorInfo(season.seasonDubbed)
                            },
                        )
                    }
                }

                if (episodes.size == 25) nextPage += 1 else nextPage = -1
            } while (nextPage != -1)
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val dubs = listOf(
            Pair("Legendado", episode.url.toHttpUrl().queryParameter("episode[0]")),
            Pair("Dublado", episode.url.toHttpUrl().queryParameter("episode[1]")),
        )

        val videos = mutableListOf<Video>()

        dubs.forEach { dub ->
            if (dub.second.isNullOrBlank()) {
                return@forEach
            }
            val request =
                GET("$baseUrl/v2/anime/episode/${dub.second}/stream", headers = headers)
            val response = client.newCall(request).execute().parseAs<EpisodeInfoDto>()

            response.streams.shd?.let { url ->
                videos.add(
                    Video(
                        url,
                        "${dub.first} - 480p",
                        videoUrl = url,
                        headers = streamHeaders(),
                    ),
                )
            }
            response.streams.mhd?.let { url ->
                videos.add(
                    Video(
                        url,
                        "${dub.first} - 720p",
                        videoUrl = url,
                        headers = streamHeaders(),
                    ),
                )
            }
            response.streams.fhd?.let { url ->
                videos.add(
                    Video(
                        url,
                        "${dub.first} - 1080p",
                        videoUrl = url,
                        headers = streamHeaders(),
                    ),
                )
            }
        }

        return videos.sort()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = PREF_LANGUAGE_TITLE
            entries = PREF_LANGUAGE_VALUES
            entryValues = PREF_LANGUAGE_VALUES
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
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
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(lang) }
                .thenByDescending { it.quality.contains(quality) }
                .thenByDescending {
                    REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    private fun getScanlatorInfo(seasonDubbed: Int): String = when (seasonDubbed) {
        0 -> "Legendado"
        1 -> "Dublado"
        else -> "Legendado"
    }

    private fun updateScanlatorInfo(currentScanlator: String?, seasonDubbed: Int): String {
        val newType = when (seasonDubbed) {
            0 -> "Legendado"
            1 -> "Dublado"
            else -> "Legendado"
        }

        return when {
            currentScanlator.isNullOrBlank() -> newType
            currentScanlator.contains("Legendado") && newType == "Dublado" -> "Legendado e Dublado"
            currentScanlator.contains("Dublado") && newType == "Legendado" -> "Legendado e Dublado"
            currentScanlator.contains("Legendado e Dublado") -> "Legendado e Dublado"
            else -> currentScanlator
        }
    }

    private fun Response.createNewWithCompatBody(outputStream: ByteArray, contentType: MediaType?) = this.newBuilder()
        .body(outputStream.toResponseBody(contentType))
        .removeHeader("Content-Encoding")
        .build()

    private fun generateRandomIp(): String = (1..4).map { Random.nextInt(0, 254) }.joinToString(".")

    private fun feedRequest() = GET("$baseUrl/v2/animes/feed", headers = headers)

    private fun streamHeaders(): Headers = Headers.headersOf(
        "Accept",
        "*/*",
        "User-Agent",
        STREAM_USER_AGENT,
    )

    private fun Response.parseFeedSection(type: Int): List<FeedItemDto> = parseAs<FeedResponseDto>().data.find { it.type == type }?.data.orEmpty()

    private fun FeedItemDto.toPopularAnime(): SAnime? {
        val id = animeId ?: return null
        return SAnime.create().apply {
            setUrlWithoutDomain("$baseUrl/v2/anime/$id")
            title = animeName.orEmpty()
            thumbnail_url = thumbnail
        }
    }

    private fun FeedItemDto.toLatestAnime(): SAnime? {
        val id = epAnimeId ?: return null
        return SAnime.create().apply {
            setUrlWithoutDomain("$baseUrl/v2/anime/$id")
            title = animeName.orEmpty()
            thumbnail_url = thumbnail
        }
    }

    companion object {
        private const val FEED_TYPE_POPULAR = 3
        private const val FEED_TYPE_LATEST = 7
        private const val STREAM_USER_AGENT = "Lavf/60.3.100"

        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }

        // Public test-account token required by the API; not a production credential.
        private const val TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpZCI6MTMxNjUzODQsInV1aWQiOiI4N2VmNmNmMC1jMjFkLTExZWYtODAxNS01NzNlMjdjNWU4ZGIiLCJpYXQiOjE3MzUwNjMwNTd9.5JMhTqBjs4A3VxrIjNQqpXtJGJ5y8MJt-ARvFrjcYUo"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("480p", "720p", "1080p")

        private const val PREF_LANGUAGE_KEY = "pref_language"
        private const val PREF_LANGUAGE_DEFAULT = "Legendado"
        private const val PREF_LANGUAGE_TITLE = "Língua/tipo preferido"
        private val PREF_LANGUAGE_VALUES = arrayOf("Legendado", "Dublado")
    }
}
