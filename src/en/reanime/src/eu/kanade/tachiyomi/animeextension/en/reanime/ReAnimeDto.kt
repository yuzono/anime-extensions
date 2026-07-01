package eu.kanade.tachiyomi.animeextension.en.reanime

import eu.kanade.tachiyomi.animeextension.en.reanime.ReAnime.Companion.parseStatus
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ReAnimeSearchResponseDto(
    @SerialName("limit") val limit: Int,
    @SerialName("offset") val offset: Int,
    @SerialName("processing_ms") val processingMs: Int? = null,
    @SerialName("query") val query: String? = null,
    @SerialName("results") val results: List<ReAnimeAnimeDto>,
    @SerialName("total") val total: Int,
)

@Serializable
data class ReAnimeLatestDto(
    val data: List<ReAnimeAnimeDto>,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class ReAnimeAnimeDto(
    @SerialName("anime_id") val animeId: String,
    val title: ReAnimeTitleDto? = null,
    @SerialName("cover_image") val coverImage: ReAnimeCoverDto? = null,
    val description: String? = null,
    val format: String? = null,
    val status: String? = null,
    val genres: List<String>? = null,
    val season: String? = null,
    @SerialName("season_year") val seasonYear: Int? = null,
    val episode: ReAnimeEpisodeDto? = null,
) {
    fun toSAnime(titleLanguage: String): SAnime? = SAnime.create().apply {
        val dto = this@ReAnimeAnimeDto
        url = dto.animeId
        title = dto.title?.preferredTitle(titleLanguage) ?: return null
        thumbnail_url = dto.coverImage?.extraLarge ?: dto.coverImage?.large
        genre = dto.genres?.joinToString().takeIf { !it.isNullOrBlank() }
        status = parseStatus(dto.status)
        description = dto.description?.takeIf { it.isNotBlank() }
    }
}

@Serializable
data class ReAnimeTitleDto(
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
data class ReAnimeCoverDto(
    @SerialName("extra_large") val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
data class ReAnimeEpisodeListDto(
    val data: List<ReAnimeEpisodeDto>,
    val total: Int,
)

@Serializable
data class ReAnimeEpisodeDto(
    val episodeId: String? = null,
    val episode_number: Double,
    val title: String = "",
    val aired: String? = null,
    val is_filler: Boolean = false,
    val is_recap: Boolean = false,
)

// ======================== Video Server DTOs ========================

@Serializable
data class ReAnimeVideoResponseDto(
    val success: Boolean,
    val servers: List<ReAnimeVideoServerDto>? = null,
)

@Serializable
data class ReAnimeVideoServerDto(
    @SerialName("\$id") val id: String? = null,
    @SerialName("serverName") val serverName: String? = null,
    @SerialName("dataLink") val dataLink: String? = null,
    @SerialName("dataType") val dataType: String? = null,
    @SerialName("continue") val continueFlag: Boolean = false,
    @SerialName("softsub") val softsub: Boolean = false,
)

// ======================== Anime Detail DTOs ========================

@Serializable
data class ReAnimeAnimeDetailDto(
    @SerialName("anime_id") val animeId: String,
    @SerialName("anilist_id") val anilistId: Int? = null,
    @SerialName("mal_id") val malId: Int? = null,
    @SerialName("kitsu_id") val kitsuId: Int? = null,
    @SerialName("anidb_id") val anidbId: Int? = null,
    @SerialName("anime_planet_id") val animePlanetId: String? = null,
    @SerialName("animecountdown_id") val animeCountdownId: Int? = null,
    @SerialName("animenewsnetwork_id") val animeNewsNetworkId: Int? = null,
    @SerialName("anisearch_id") val anisearchId: Int? = null,
    @SerialName("simkl_id") val simklId: Int? = null,
    @SerialName("themoviedb_id") val tmdbId: Int? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val title: ReAnimeTitleDto? = null,
    @SerialName("cover_image") val coverImage: ReAnimeCoverDto? = null,
    @SerialName("banner_image") val bannerImage: String? = null,
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
    val studios: List<ReAnimeStudioDto>? = null,
    val relations: List<ReAnimeRelationDto>? = null,
    @SerialName("start_date") val startDate: ReAnimeDateDto? = null,
    @SerialName("end_date") val endDate: ReAnimeDateDto? = null,
    val synonyms: List<String>? = null,
    val trailer: ReAnimeTrailerDto? = null,
    @SerialName("external_links") val externalLinks: List<ReAnimeExternalLinkDto>? = null,
) {
    fun toSAnime(titleLanguage: String): SAnime = SAnime.create().apply {
        val dto = this@ReAnimeAnimeDetailDto
        url = dto.animeId
        title = dto.title?.preferredTitle(titleLanguage) ?: dto.animeId
        thumbnail_url = dto.coverImage?.extraLarge ?: dto.coverImage?.large
        genre = dto.genres?.joinToString().takeIf { !it.isNullOrBlank() }
        status = parseStatus(dto.status)
        author = dto.studios?.filter { it.isMain == true }?.mapNotNull { it.name }?.joinToString(", ")?.takeIf { it.isNotBlank() }
    }
}

@Serializable
data class ReAnimeStudioDto(
    val id: Int? = null,
    val name: String? = null,
    @SerialName("is_main") val isMain: Boolean? = null,
)

@Serializable
data class ReAnimeDateDto(
    val day: Int? = null,
    val month: Int? = null,
    val year: Int? = null,
)

@Serializable
data class ReAnimeTrailerDto(
    val id: String? = null,
    val site: String? = null,
)

@Serializable
data class ReAnimeExternalLinkDto(
    val id: Int? = null,
    val site: String? = null,
    val url: String? = null,
    val type: String? = null,
)

@Serializable
data class ReAnimeRelationDto(
    @SerialName("anime_id") val animeId: String,
    val title: ReAnimeTitleDto? = null,
    @SerialName("cover_image") val coverImage: ReAnimeCoverDto? = null,
    val format: String? = null,
    val season: String? = null,
    @SerialName("season_year") val seasonYear: Int? = null,
)

// ======================== Recommendations DTOs ========================

@Serializable
data class ReAnimeRecommendationsDto(
    val recommendations: List<ReAnimeRecommendationDto>,
    val success: Boolean,
)

@Serializable
data class ReAnimeRecommendationDto(
    val id: String,
    val title: ReAnimeRecTitleDto,
    @SerialName("cover_image") val coverImage: ReAnimeCoverDto? = null,
    val format: String? = null,
    val year: Int? = null,
    val status: String? = null,
    val genres: List<String>? = null,
    @SerialName("average_score") val averageScore: Int? = null,
)

@Serializable
data class ReAnimeRecTitleDto(
    val english: String? = null,
    val romaji: String? = null,
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

// ======================== FlixCloud Decryption DTOs ========================

@Serializable
data class DecFlixCloudTokenResponseDto(
    val status: Int,
    val result: DecFlixCloudTokenResultDto? = null,
)

@Serializable
data class DecFlixCloudTokenResultDto(
    val token: String = "",
    val context: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class DecFlixCloudStreamResponseDto(
    val status: Int,
    val result: DecFlixCloudStreamResultDto? = null,
)

@Serializable
data class DecFlixCloudStreamResultDto(
    val stream: String = "",
    val context: JsonObject = JsonObject(emptyMap()),
)
