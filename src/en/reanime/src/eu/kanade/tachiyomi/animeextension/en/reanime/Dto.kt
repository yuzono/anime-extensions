package eu.kanade.tachiyomi.animeextension.en.reanime

import android.util.LruCache
import eu.kanade.tachiyomi.animeextension.en.reanime.ReAnime.Companion.parseStatus
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
class SearchResponseDto(
    val limit: Int,
    val offset: Int,
    val results: List<AnimeDto>,
    val total: Int,
)

@Serializable
class LatestDto(
    val data: List<AnimeDto>,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
class AnimeDto(
    @SerialName("anime_id") private val animeId: String,
    private val title: TitleDto? = null,
    @SerialName("cover_image") private val coverImage: CoverDto? = null,
    private val description: String? = null,
    val status: String? = null,
    private val genres: List<String>? = null,
) {
    fun toSAnime(titleLanguage: String): SAnime? = SAnime.create().apply {
        url = animeId
        title = this@AnimeDto.title?.preferredTitle(titleLanguage) ?: return null
        thumbnail_url = coverImage?.let { c ->
            if (c.safeExtraLarge?.contains("tmdb.org") == true) {
                c.safeLarge ?: c.safeMedium ?: c.safeExtraLarge
            } else {
                c.safeExtraLarge ?: c.safeLarge ?: c.safeMedium
            }
        }
        genre = genres?.joinToString().takeIf { !it.isNullOrBlank() }
        status = parseStatus(this@AnimeDto.status)
        description = this@AnimeDto.description?.takeIf { it.isNotBlank() }
    }
}

@Serializable
class TitleDto(
    val english: String? = null,
    val native: String? = null,
    val romaji: String? = null,
) {
    fun preferredTitle(language: String): String? {
        val preferred = when (language) {
            "english" -> english
            "native" -> native
            "romaji" -> romaji
            else -> null
        }?.takeIf(String::isNotBlank)

        return preferred ?: listOfNotNull(romaji, english, native).firstOrNull(String::isNotBlank)
    }
}

@Serializable
class CoverDto(
    @SerialName("extra_large") val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
) {
    val safeExtraLarge: String? get() = extraLarge?.takeIf(String::isNotBlank)
    val safeLarge: String? get() = large?.takeIf(String::isNotBlank)
    val safeMedium: String? get() = medium?.takeIf(String::isNotBlank)
}

@Serializable
class EpisodeListDto(
    val data: List<EpisodeDto>,
)

@Serializable
class EpisodeDto(
    val episodeId: String? = null,
    @SerialName("episode_number") val episodeNumber: Double,
    val title: String = "",
    val aired: String? = null,
    @SerialName("is_filler") val isFiller: Boolean = false,
    @SerialName("is_recap") val isRecap: Boolean = false,
)

// ======================== Video Server DTOs ========================

@Serializable
class VideoResponseDto(
    val success: Boolean,
    val servers: List<VideoServerDto>? = null,
)

@Serializable
class VideoServerDto(
    val serverName: String? = null,
    val dataLink: String? = null,
    val dataType: String? = null,
    val softsub: Boolean = false,
)

// ======================== Anime Detail DTOs ========================

@Serializable
class AnimeDetailDto(
    @SerialName("anime_id") val animeId: String,
    @SerialName("anilist_id") val anilistId: Int? = null,
    @SerialName("mal_id") val malId: Int? = null,
    @SerialName("kitsu_id") val kitsuId: Int? = null,
    @SerialName("anidb_id") val anidbId: Int? = null,
    @SerialName("anime_planet_id") val animePlanetId: String? = null,
    @SerialName("animenewsnetwork_id") val animeNewsNetworkId: Int? = null,
    @SerialName("anisearch_id") val anisearchId: Int? = null,
    @SerialName("simkl_id") val simklId: Int? = null,
    @SerialName("themoviedb_id") val tmdbId: Int? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val title: TitleDto? = null,
    @SerialName("cover_image") private val coverImage: CoverDto? = null,
    val description: String? = null,
    val format: String? = null,
    val status: String? = null,
    val genres: List<String>? = null,
    val season: String? = null,
    @SerialName("season_year") val seasonYear: Int? = null,
    val duration: Int? = null,
    val subbed: Int? = null,
    val dubbed: Int? = null,
    @SerialName("average_score") val averageScore: Int? = null,
    val rating: String? = null,
    private val studios: List<StudioDto>? = null,
    val relations: List<RelationDto>? = null,
    val synonyms: List<String>? = null,
    val trailer: TrailerDto? = null,
    @SerialName("external_links") val externalLinks: List<ExternalLinkDto>? = null,
) {
    fun toSAnime(titleLanguage: String): SAnime = SAnime.create().apply {
        url = animeId
        title = this@AnimeDetailDto.title?.preferredTitle(titleLanguage) ?: animeId
        thumbnail_url = coverImage?.let { c ->
            if (c.safeExtraLarge?.contains("tmdb.org") == true) {
                c.safeLarge ?: c.safeMedium ?: c.safeExtraLarge
            } else {
                c.safeExtraLarge ?: c.safeLarge ?: c.safeMedium
            }
        }
        genre = genres?.joinToString().takeIf { !it.isNullOrBlank() }
        status = parseStatus(this@AnimeDetailDto.status)
        author = studios?.filter { it.isMain == true }?.mapNotNull { it.name }?.joinToString(", ")?.takeIf { it.isNotBlank() }
    }
}

@Serializable
class StudioDto(
    val name: String? = null,
    @SerialName("is_main") val isMain: Boolean? = null,
)

@Serializable
class TrailerDto(
    val id: String? = null,
    val site: String? = null,
)

@Serializable
class ExternalLinkDto(
    val site: String? = null,
    val url: String? = null,
    val type: String? = null,
)

@Serializable
class RelationDto(
    @SerialName("anime_id") val animeId: String,
    val title: TitleDto? = null,
    @SerialName("cover_image") val coverImage: CoverDto? = null,
    val format: String? = null,
    val season: String? = null,
    @SerialName("season_year") val seasonYear: Int? = null,
)

// ======================== Recommendations DTOs ========================

@Serializable
class RecommendationsDto(
    val recommendations: List<RecommendationDto>,
    val success: Boolean,
)

@Serializable
class RecommendationDto(
    val id: String,
    val title: RecTitleDto,
    @SerialName("cover_image") val coverImage: CoverDto? = null,
    val status: String? = null,
    val genres: List<String>? = null,
)

@Serializable
class RecTitleDto(
    private val english: String? = null,
    private val romaji: String? = null,
) {
    fun preferredTitle(language: String): String? {
        val preferred = when (language) {
            "english" -> english
            "romaji" -> romaji
            else -> null
        }?.takeIf(String::isNotBlank)

        return preferred ?: listOfNotNull(romaji, english).firstOrNull(String::isNotBlank)
    }
}

// ======================== FlixCloud Embed Data DTOs ========================

@Serializable
class FlixcloudChapterDto(
    val start: Long? = null,
    val end: Long? = null,
)

@Serializable
class FlixcloudSubtitleDto(
    val url: String,
    val language: String? = null,
)

@Serializable
class FlixcloudEmbedDataDto(
    val subtitles: List<FlixcloudSubtitleDto>? = null,
    @SerialName("intro_chapter") private val introChapter: FlixcloudChapterDto? = null,
    @SerialName("outro_chapter") private val outroChapter: FlixcloudChapterDto? = null,
) {
    fun toSkipTimes(): SkipTimes = SkipTimes(
        introStart = introChapter?.start,
        introEnd = introChapter?.end,
        outroStart = outroChapter?.start,
        outroEnd = outroChapter?.end,
    )
}

val skipTimesCache = LruCache<String, SkipTimes>(64)

class SkipTimes(
    val introStart: Long? = null,
    val introEnd: Long? = null,
    val outroStart: Long? = null,
    val outroEnd: Long? = null,
)

// ======================== FlixCloud Decryption DTOs ========================

@Serializable
class DecFlixCloudTokenResponseDto(
    val status: Int,
    val result: DecFlixCloudTokenResultDto? = null,
)

@Serializable
class DecFlixCloudTokenResultDto(
    val token: String = "",
    val context: JsonObject = JsonObject(emptyMap()),
)

@Serializable
class DecFlixCloudStreamResponseDto(
    val status: Int,
    val result: DecFlixCloudStreamResultDto? = null,
)

@Serializable
class DecFlixCloudStreamResultDto(
    val stream: String = "",
    val context: JsonObject = JsonObject(emptyMap()),
)
