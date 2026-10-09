package eu.kanade.tachiyomi.animeextension.en.anikura

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class AnimeItemDto(
    val id: Int,
    val slug: String,
    val title: String,
    val poster: String? = null,
    @SerialName("background_image") val backgroundImage: String? = null,
    val description: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    @SerialName("terms_by_type") val termsByType: TermsDto? = null,
) {
    fun toSAnime(baseUrl: String): SAnime {
        val anime = SAnime.create()
        anime.url = "$id/$slug"
        anime.title = title
        anime.thumbnail_url = poster.absolute(baseUrl) ?: backgroundImage.absolute(baseUrl)
        anime.description = description?.replace(TAG_REGEX, "")?.trim()
        anime.genre = (termsByType?.genre ?: genres).joinToString()
        anime.status = parseStatus(status)
        return anime
    }
}

@Serializable
class TermsDto(
    val genre: List<String> = emptyList(),
)

@Serializable
class BrowsePageDto(
    val items: List<AnimeItemDto> = emptyList(),
)

@Serializable
class AnimeRowDto(
    val title: String = "",
    val episodes: List<LatestEpisodeDto> = emptyList(),
)

@Serializable
class LatestEpisodeDto(
    @SerialName("catalogId") val catalogId: Int,
    val slug: String,
    @SerialName("seriesTitle") val seriesTitle: String,
    val poster: String? = null,
    val banner: String? = null,
) {
    fun toSAnime(baseUrl: String): SAnime {
        val anime = SAnime.create()
        anime.url = "$catalogId/$slug"
        anime.title = seriesTitle
        anime.thumbnail_url = poster.absolute(baseUrl) ?: banner.absolute(baseUrl)
        return anime
    }
}

// `episodes` on this object is a string count, not an array.
@Serializable
class AnimeCoreDto(
    val id: Int,
    val slug: String,
    val title: String,
    val poster: String? = null,
    @SerialName("background_image") val backgroundImage: String? = null,
    val description: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    @SerialName("terms_by_type") val termsByType: TermsDto? = null,
) {
    fun toSAnime(baseUrl: String): SAnime {
        val anime = SAnime.create()
        anime.url = "$id/$slug"
        anime.title = title
        anime.thumbnail_url = poster.absolute(baseUrl) ?: backgroundImage.absolute(baseUrl)
        anime.description = description?.replace(TAG_REGEX, "")?.trim()
        anime.genre = (termsByType?.genre ?: genres).joinToString()
        anime.status = parseStatus(status)
        return anime
    }
}

@Serializable
class AnimeHeroPropsDto(
    val anime: AnimeCoreDto,
)

// `episodeThumbnails` and `episodeDescriptions` are keyed by episode number.
@Serializable
class EpisodeListPropsDto(
    val episodes: List<EpisodeDto> = emptyList(),
    val episodeThumbnails: Map<String, String> = emptyMap(),
    val episodeDescriptions: Map<String, String> = emptyMap(),
)

@Serializable
class EpisodeDto(
    val number: Int,
    val title: String,
) {
    fun toSEpisode(
        sAnimeUrl: String,
        lang: String,
        previewUrl: String? = null,
        summary: String? = null,
    ) = SEpisode.create().apply {
        url = "/watch/$sAnimeUrl?ep=$number&lang=$lang"
        name = buildDisplayName()
        episode_number = number.toFloat()
        this@apply.preview_url = previewUrl
        this@apply.summary = summary
    }

    private fun buildDisplayName(): String {
        val generic = "Episode $number"
        val trimmed = title.trim()
        return when {
            trimmed.isEmpty() -> generic
            trimmed.equals(generic, ignoreCase = true) -> generic
            else -> "$number - $trimmed"
        }
    }
}

@Serializable
class StreamsResponseDto(
    val streams: List<StreamDto> = emptyList(),
)

@Serializable
class SourceResponseDto(
    val stream: StreamDto? = null,
)

@Serializable
class StreamDto(
    val id: String,
    val label: String,
    val url: String,
    val tracks: List<SubtitleDto> = emptyList(),
    @SerialName("embedUrl") val embedUrl: String? = null,
) {
    fun toSubtitleTracks(baseUrl: String): List<Track> = tracks.mapNotNull { t ->
        val url = t.url.absolute(baseUrl) ?: return@mapNotNull null
        val display = t.label.ifBlank { t.language ?: "Unknown" }
        Track(url = url, lang = display)
    }
}

@Serializable
class SubtitleDto(
    val label: String = "",
    val language: String? = null,
    val url: String,
)

private val TAG_REGEX = Regex("<[^>]+>")

private fun String?.absolute(baseUrl: String): String? {
    val trimmed = this?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("/")) "$baseUrl$trimmed" else trimmed
}

private fun parseStatus(raw: String?): Int {
    val s = raw?.lowercase() ?: return SAnime.UNKNOWN
    return when {
        s.contains("currently") || s == "releasing" -> SAnime.ONGOING
        s.contains("finished") -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }
}
