package eu.kanade.tachiyomi.animeextension.ru.aniliberty

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
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class Aniliberty :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniLiberty"

    override val baseUrl = "https://anilibria.top"

    override val lang = "ru"

    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api/v1"

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json")

    // ─── Popular / Latest ─────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = catalogRequest(page, "RATING_DESC")

    override fun popularAnimeParse(response: Response): AnimesPage = catalogParse(response)

    override fun latestUpdatesRequest(page: Int): Request = catalogRequest(page, "FRESH_AT_DESC")

    override fun latestUpdatesParse(response: Response): AnimesPage = catalogParse(response)

    private fun catalogRequest(page: Int, sorting: String): Request {
        val url = "$apiUrl/anime/catalog/releases".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("f[sorting]", sorting)
            .build()
        return GET(url.toString(), headers)
    }

    private fun catalogParse(response: Response): AnimesPage {
        val result = response.parseAs<CatalogResponse>()
        val animes = result.data.map { it.toSAnime() }
        val pagination = result.meta?.pagination
        val hasNext = pagination != null && pagination.currentPage < pagination.totalPages
        return AnimesPage(animes, hasNext)
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$apiUrl/app/search/releases".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .build()
            return GET(url.toString(), headers)
        }

        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart().orEmpty()
        val url = "$apiUrl/anime/catalog/releases".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("f[sorting]", "FRESH_AT_DESC")
            .apply { if (genre.isNotEmpty()) addQueryParameter("f[genres]", genre) }
            .build()
        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        // The search endpoint returns a bare array; the catalog (filters branch) returns {data,meta}.
        val body = response.use { it.body.string() }
        return if (body.trimStart().startsWith("[")) {
            AnimesPage(body.parseAs<List<Release>>().map { it.toSAnime() }, false)
        } else {
            val result = body.parseAs<CatalogResponse>()
            val pagination = result.meta?.pagination
            val hasNext = pagination != null && pagination.currentPage < pagination.totalPages
            AnimesPage(result.data.map { it.toSAnime() }, hasNext)
        }
    }

    // ─── Details ──────────────────────────────────────────────────────────────

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/anime/releases/${anime.url.releaseId()}", headers)

    override fun getAnimeUrl(anime: SAnime): String {
        val alias = anime.url.substringAfter('|', "")
        val ref = alias.ifBlank { anime.url.releaseId() }
        return "$baseUrl/anime/releases/release/$ref"
    }

    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<Release>().toSAnime()

    // ─── Episodes ─────────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request = GET("$apiUrl/anime/releases/${anime.url.releaseId()}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val release = response.parseAs<Release>()
        return release.episodes.mapNotNull { ep ->
            val epId = ep.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SEpisode.create().apply {
                url = "${release.id}|$epId"
                val base = if (release.type?.value == "MOVIE") {
                    "Фильм"
                } else {
                    "Серия ${ep.ordinal?.formatOrdinal() ?: ""}".trim()
                }
                val title = ep.name?.takeIf { it.isNotBlank() }
                name = if (title != null) "$base. $title" else base
                episode_number = ep.ordinal ?: 0f
            }
        }.sortedByDescending { it.episode_number }
    }

    // ─── Videos ───────────────────────────────────────────────────────────────

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val releaseId = episode.url.substringBefore('|')
        val episodeId = episode.url.substringAfter('|')

        val release = client.newCall(GET("$apiUrl/anime/releases/$releaseId", headers))
            .awaitSuccess().parseAs<Release>()

        val ep = release.episodes.firstOrNull { it.id == episodeId }
            ?: return emptyList()

        val videoHeaders = Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", "$baseUrl/")
            .build()

        return listOfNotNull(
            ep.hls1080?.let { Video(it, "1080p", it, headers = videoHeaders) },
            ep.hls720?.let { Video(it, "720p", it, headers = videoHeaders) },
            ep.hls480?.let { Video(it, "480p", it, headers = videoHeaders) },
        ).sort()
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedByDescending { it.quality.contains(quality) }
    }

    // ─── Mappers ──────────────────────────────────────────────────────────────

    private fun Release.toSAnime(): SAnime = SAnime.create().apply {
        url = "$id|${alias.orEmpty()}"
        title = name?.main?.takeIf { it.isNotBlank() }
            ?: name?.english?.takeIf { it.isNotBlank() }
            ?: "Без названия"
        thumbnail_url = poster?.bestSrc()?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
        description = buildString {
            year?.let { append("Год: $it\n") }
            type?.description?.let { append("Тип: $it\n") }
            if (isNotEmpty()) append("\n")
            append(this@toSAnime.description.orEmpty())
        }.trim().ifBlank { null }
        genre = genres.mapNotNull { it.name }.joinToString().ifBlank { null }
        status = if (isOngoing) SAnime.ONGOING else SAnime.COMPLETED
    }

    private fun String.releaseId(): String = substringBefore('|')

    private fun Float.formatOrdinal(): String = if (this % 1f == 0f) toInt().toString() else toString()

    // ─── Filters ──────────────────────────────────────────────────────────────

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Фильтр игнорируется при текстовом поиске"),
        GenreFilter(),
    )

    private class GenreFilter : AnimeFilter.Select<String>("Жанр", GENRES.map { it.first }.toTypedArray()) {
        fun toUriPart() = GENRES[state].second
    }

    // ─── Settings ─────────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Предпочитаемое качество / Preferred quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p"),
            entryValues = listOf("1080", "720", "480"),
        )
    }

    companion object {
        private const val PAGE_LIMIT = 30
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        // id values come from /api/v1/anime/catalog/references/genres
        private val GENRES = listOf(
            "Любой" to "",
            "Сёнен" to "4",
            "Комедия" to "1",
            "Экшен" to "14",
            "Фэнтези" to "29",
            "Приключения" to "27",
            "Сверхъестественное" to "28",
            "Супер сила" to "21",
        )
    }
}

// ─── API DTOs ──────────────────────────────────────────────────────────────────

@Serializable
private data class CatalogResponse(
    val data: List<Release> = emptyList(),
    val meta: Meta? = null,
)

@Serializable
private data class Meta(val pagination: Pagination? = null)

@Serializable
private data class Pagination(
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable
private data class Release(
    val id: Int,
    val alias: String? = null,
    val name: Name? = null,
    val poster: Poster? = null,
    val description: String? = null,
    val year: Int? = null,
    val type: Labeled? = null,
    @SerialName("is_ongoing") val isOngoing: Boolean = false,
    val genres: List<Genre> = emptyList(),
    val episodes: List<Episode> = emptyList(),
)

@Serializable
private data class Name(
    val main: String? = null,
    val english: String? = null,
    val alternative: String? = null,
)

@Serializable
private data class Poster(
    val src: String? = null,
    val optimized: Poster? = null,
) {
    fun bestSrc(): String? = optimized?.src ?: src
}

@Serializable
private data class Labeled(
    val value: String? = null,
    val description: String? = null,
)

@Serializable
private data class Genre(val id: Int? = null, val name: String? = null)

@Serializable
private data class Episode(
    val id: String? = null,
    val name: String? = null,
    val ordinal: Float? = null,
    @SerialName("hls_480") val hls480: String? = null,
    @SerialName("hls_720") val hls720: String? = null,
    @SerialName("hls_1080") val hls1080: String? = null,
)
