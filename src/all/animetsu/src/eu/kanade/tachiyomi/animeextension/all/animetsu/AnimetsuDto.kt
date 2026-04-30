package eu.kanade.tachiyomi.animeextension.all.animetsu

import eu.kanade.tachiyomi.animesource.model.SEpisode
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
data class AnimetsuSearchDto(
    val results: List<AnimetsuAnimeDto>,
    val page: Int,
    @SerialName("last_page") val lastPage: Int,
    val total: Int,
)

@Serializable
data class AnimetsuRecentDto(
    val results: List<AnimetsuAnimeDto>,
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
)

@Serializable
data class AnimetsuAnimeDto(
    val id: String,
    val type: String? = null,
    val title: AnimetsuTitleDto? = null,
    val status: String? = null,
    @SerialName("is_adult") val isAdult: Boolean = false,
    @SerialName("cover_image") val coverImage: AnimetsuCoverDto? = null,
    val banner: String? = null,
    val description: String? = null,
    @SerialName("total_eps") val totalEps: Int? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val year: Int? = null,
    val format: String? = null,
    val duration: Int? = null,
    val genres: List<String>? = null,
    val tags: List<String>? = null,
    @SerialName("average_score") val averageScore: Int? = null,
    val trailer: String? = null,
    val season: String? = null,
    val seasons: List<AnimetsuSeasonDto>? = null,
    val episodes: List<AnimetsuEpisodeDto>? = null,
    @SerialName("anilist_id") val anilistId: Int? = null,
    @SerialName("mal_id") val malId: Int? = null,
    val country: String? = null,
    val source: String? = null,
    val hashtag: String? = null,
    @SerialName("mean_score") val meanScore: Int? = null,
    val popularity: Int? = null,
    val favourites: Int? = null,
    val trending: Int? = null,
    val synonyms: List<String>? = null,
    val studios: List<AnimetsuStudioDto>? = null,
    val relations: List<AnimetsuRelationDto>? = null,
    val characters: List<AnimetsuCharacterDto>? = null,
    val recommendations: List<AnimetsuRecommendationDto>? = null,
    val staff: List<AnimetsuStaffDto>? = null,
    val color: String? = null,
    @SerialName("clear_logo") val clearLogo: String? = null,
    val users: Int? = null,
)

@Serializable
data class AnimetsuTitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class AnimetsuCoverDto(
    val large: String? = null,
    val medium: String? = null,
    val small: String? = null,
)

@Serializable
data class AnimetsuSeasonDto(
    val id: String,
    val title: AnimetsuTitleDto? = null,
    val status: String? = null,
    val relation: String? = null,
)

@Serializable
data class AnimetsuEpisodeDto(
    @SerialName("ep_num") val epNum: Double? = null, // Was Int?
    @SerialName("aired_at") val airedAt: String? = null,
    val desc: String? = null,
    @SerialName("is_filler") val isFiller: Boolean? = null,
    val name: String? = null,
    val id: String = "",
) {
    companion object {
        private val DATE_FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun toSEpisode(animeId: String): SEpisode? {
        val epNum = this.epNum ?: return null
        if (epNum <= 0.0) return null // That Time I Got Reincarnated as a Slime Season 3 has an Episode 0 that does not exist
        val dtoName = this.name
        val dtoFiller = this.isFiller
        val dtoAiredAt = this.airedAt

        // Format: 17.5 → "17.5", 1.0 → "1"; e.g. That Time I Got Reincarnated as a Slime Season 3
        val epNumStr = if (epNum % 1.0 == 0.0) epNum.toInt().toString() else epNum.toString()

        return SEpisode.create().apply {
            url = "$animeId/$epNum"
            name = buildString {
                append("Ep. $epNumStr")
                if (!dtoName.isNullOrBlank()) append(" - $dtoName")
                if (dtoFiller == true) append(" (Filler)")
            }
            episode_number = epNum.toFloat()
            date_upload = DATE_FORMATTER.tryParse(dtoAiredAt) // Now uses the shared constant
        }
    }
}

@Serializable
data class AnimetsuServerDto(
    val id: String,
    val default: Boolean = false,
    val tip: String? = null,
)

@Serializable
data class AnimetsuVideoDto(
    val sources: List<AnimetsuSourceDto>,
    val subs: List<AnimetsuSubDto>? = null,
    val skips: AnimetsuSkipsDto? = null,
    val from: String? = null,
    val server: String? = null,
)

@Serializable
data class AnimetsuSourceDto(
    val quality: String,
    val url: String,
    @SerialName("old_hls") val oldHls: Boolean = false,
    val type: String? = null,
    @SerialName("need_proxy") val needProxy: Boolean = false,
)

@Serializable
data class AnimetsuSubDto(
    val url: String,
    val lang: String? = null,
)

@Serializable
data class AnimetsuSkipsDto(
    val intro: AnimetsuSkipTimeDto? = null,
    val outro: AnimetsuSkipTimeDto? = null,
    @SerialName("ep_num") val epNum: Int? = null,
)

@Serializable
data class AnimetsuSkipTimeDto(
    val start: Double,
    val end: Double,
)

@Serializable
data class AnimetsuStudioDto(
    val name: String,
    @SerialName("anilist_id") val anilistId: Int? = null,
    @SerialName("is_main") val isMain: Boolean = false,
)

@Serializable
data class AnimetsuRelationDto(
    val id: String,
    @SerialName("anilist_id") val anilistId: Int? = null,
    val title: AnimetsuTitleDto? = null,
    val format: String? = null,
    val season: String? = null,
    val year: Int? = null,
    @SerialName("relation_type") val relationType: String? = null,
    @SerialName("total_eps") val totalEps: Int? = null,
    val status: String? = null,
)

@Serializable
data class AnimetsuCharacterDto(
    @SerialName("anilist_id") val anilistId: Int? = null,
    val name: String? = null,
    val image: String? = null,
    val role: String? = null,
    @SerialName("voice_actor") val voiceActor: AnimetsuVoiceActorDto? = null,
)

@Serializable
data class AnimetsuVoiceActorDto(
    @SerialName("anilist_id") val anilistId: Int? = null,
    val name: String? = null,
    val image: String? = null,
    val language: String? = null,
)

@Serializable
data class AnimetsuRecommendationDto(
    val id: String,
    @SerialName("anilist_id") val anilistId: Int? = null,
    val title: AnimetsuTitleDto? = null,
    val format: String? = null,
    val season: String? = null,
    val year: Int? = null,
    @SerialName("total_eps") val totalEps: Int? = null,
    val status: String? = null,
    @SerialName("average_score") val averageScore: Int? = null,
    val description: String? = null,
    @SerialName("cover_image") val coverImage: AnimetsuCoverDto? = null,
    val banner: String? = null,
    val trailer: String? = null,
)

@Serializable
data class AnimetsuStaffDto(
    @SerialName("anilist_id") val anilistId: Int? = null,
    val name: String? = null,
    val image: String? = null,
    val language: String? = null,
    val role: String? = null,
)
