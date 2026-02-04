package eu.kanade.tachiyomi.multisrc.sudatchi.dto

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EpisodeDto(
    val title: String,
    val id: Int,
    val number: Int,
    @SerialName("Subtitles")
    val subtitlesDto: List<SubtitleDto>?, // url & SubtitleLangDto
    val subtitles: List<SubtitleDto>?, // url & language
    @SerialName("AudioStreams")
    val audioStreamsDto: List<AudioDto>?, // playlistUri & AudioLangDto
    val audioStreams: List<AudioDto>?, // playlistUri & language
    /* For home page */
    val animeId: Int, // Only correct for home page, not correct for anime detail page
    val coverImage: String?, // "/image/2ca27132"
    val animeTitle: TitleDto?,
) {
    fun toEpisode(animeId: Int) = SEpisode.create().apply {
        url = "/api/anime/$animeId?episode=$id"
        name = title
        episode_number = number.toFloat()
    }

    fun toSAnime(titleLang: String, baseUrl: String) = SAnime.create().apply {
        url = "/anime/$animeId"
        title = animeTitle?.getTitle(titleLang) ?: this@EpisodeDto.title
        thumbnail_url = coverImage?.let { "$baseUrl$it" }
    }
}

@Serializable
data class AnimeDto(
    val id: String,
    val title: TitleDto,
    val status: String?, // FINISHED
    val coverImage: String?,
    @SerialName("genres")
    val animeGenres: List<String>?,
) {
    fun toSAnime(titleLang: String) = SAnime.create().apply {
        url = "/anime/$id"
        title = this@AnimeDto.title.getTitle(titleLang)
        status = this@AnimeDto.status?.parseStatus() ?: SAnime.UNKNOWN
        thumbnail_url = coverImage
        genre = animeGenres?.joinToString()
    }
}

@Serializable
data class AnimeRelatedDto(
    val id: Int,
    val title: TitleDto,
    val status: String?, // FINISHED
    val coverImage: CoverDto?,
    @SerialName("genres")
    val animeGenres: List<String>?,
) {
    fun toSAnime(titleLang: String) = SAnime.create().apply {
        url = "/anime/$id"
        title = this@AnimeRelatedDto.title.getTitle(titleLang)
        status = this@AnimeRelatedDto.status?.parseStatus() ?: SAnime.UNKNOWN
        thumbnail_url = coverImage?.extraLarge
        genre = animeGenres?.joinToString()
    }
}

@Serializable
data class AnimeDetailDto(
    val id: Int,
    val title: TitleDto,
    @SerialName("synonyms")
    val alternativeNames: List<String>?,
    val description: String?,
    val status: String, // FINISHED
    val coverImage: CoverDto?,
    val bannerImage: String?,
    @SerialName("genres")
    val animeGenres: List<String>?,
    val duration: Int?,
    val episodesCount: Int?,
    val season: String?, // WINTER
    val year: Int?,
    val averageScore: Int?,
    val nextAiring: AiringDto?,
    val trailer: TrailerDto?,
    val studio: String?,
    val country: String?, // JP
    val related: List<AnimeRelatedDto>?,
    val recommendations: List<AnimeRelatedDto>?,
    val episodes: List<EpisodeDto>,
) {
    fun toSAnime(titleLang: String) = SAnime.create().apply {
        url = "/anime/$id"
        title = this@AnimeDetailDto.title.getTitle(titleLang)
        description = StringBuilder().apply {
            this@AnimeDetailDto.description?.let { appendLine(it) }
            val details = listOfNotNull(
                trailer?.let { "[Trailer](https://youtube.com/watch?v=${it.id})" },
                country?.let { "Country: $it" },
                season?.let { "Season: $it" },
                year?.let { "Year: $it" },
                averageScore?.let { "Score: $it" },
            )
            if (details.isNotEmpty()) {
                appendLine()
                appendLine(details.joinToString("\n"))
            }

            alternativeNames?.takeIf { it.isNotEmpty() }?.let { names ->
                appendLine()
                appendLine("Alternative names:")
                appendLine(names.joinToString("\n") { "- $it" })
            }

            bannerImage?.takeIf(String::isNotBlank)?.let {
                if (isNotEmpty()) append("\n\n")
                append("![Banner]($it)")
            }
        }.toString()
        status = this@AnimeDetailDto.status.parseStatus()
        thumbnail_url = coverImage?.extraLarge ?: bannerImage
        genre = animeGenres?.joinToString()
        author = studio
    }

    @Serializable
    data class AiringDto(
        val ep: Int, // episode number
        val at: Double, // timestamp
    )
}

private fun String.parseStatus() = when (this) {
    "LICENSED", "NOT_YET_RELEASED" -> SAnime.LICENSED

    // Not Yet Released
    "AIRING", "RELEASING" -> SAnime.ONGOING

    "FINISHED" -> SAnime.COMPLETED

    "CANCELLED" -> SAnime.CANCELLED

    else -> SAnime.UNKNOWN
}

@Serializable
data class TitleDto(
    val romaji: String?,
    val english: String?,
    val native: String?,
) {
    fun getTitle(titleLang: String) = when (titleLang) {
        "romaji" -> romaji
        "japanese" -> native
        else -> english
    }
        ?: english
        ?: romaji
        ?: native
        ?: ""
}

@Serializable
data class CoverDto(
    val extraLarge: String?,
)

@Serializable
data class TrailerDto(
    val id: String, // "dQw4w9WgXcQ"
    val site: String, // "youtube"
)

@Serializable
data class HomePageDto(
    val latestEpisodes: List<EpisodeDto>,
)

@Serializable
data class SeriesDto(
    val results: List<AnimeDto>,
    val hasNextPage: Boolean,
)

@Serializable
data class SubtitleLangDto(
    val name: String, // "English"
    val language: String, // "eng"
)

@Serializable
data class SubtitleDto(
    val url: String, // "/ipfs/bafkreicmknx7fg23tvfktgo4gybh2a3dqvgk33z7wtyxnjcbar26m57kjq"
    @SerialName("SubtitlesName")
    val subtitleLang: SubtitleLangDto?,
    val language: String?,
)

@Serializable
data class AudioDto(
    val playlistUri: String, // "bafybeiegi6t2yhc4cubdztvvzb6s5vetuyisrureindkrgi7xgcmu2cmqi/audio.m3u8"
    @SerialName("Language")
    val audioLang: AudioLangDto?,
    val language: String?,
)

@Serializable
data class AudioLangDto(
    val name: String, // "English"
    val code: String, // "eng"
)
