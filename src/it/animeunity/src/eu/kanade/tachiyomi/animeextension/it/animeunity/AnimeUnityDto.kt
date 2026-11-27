package eu.kanade.tachiyomi.animeextension.it.animeunity

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class AnimePageDto(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
    val data: List<AnimeDto>,
) {
    fun toAnimesPage() = AnimesPage(data.map { it.toSAnime() }, currentPage < lastPage)
}

@Serializable
class LatestPageDto(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
    val data: List<LatestVideoDto>,
) {
    @Serializable
    class LatestVideoDto(val episode: LatestEpisodeDto)

    @Serializable
    class LatestEpisodeDto(val anime: AnimeDto)

    fun toAnimesPage() = AnimesPage(
        data.map { it.episode.anime }.distinctBy { it.id }.map { it.toSAnime() },
        currentPage < lastPage,
    )
}

@Serializable
class AnimeDto(
    val id: Int,
    val slug: String,
    val title: String,
    private val images: Images,
    private val plot: String? = null,
    private val type: String? = null,
    private val season: String? = null,
    private val year: Int? = null,
    private val score: String? = null,
    @SerialName("default_status") private val defaultStatus: String? = null,
    private val genres: List<Named> = emptyList(),
    private val studios: List<Named> = emptyList(),
) {
    @Serializable
    class Images(val poster: String? = null, val cover: String? = null)

    @Serializable
    class Named(val name: String)

    fun toSAnime() = SAnime.create().apply {
        title = this@AnimeDto.title
        url = "$id-$slug"
        thumbnail_url = images.poster ?: images.cover
    }

    fun toSAnimeDetails() = toSAnime().apply {
        status = when (defaultStatus) {
            "ongoing" -> SAnime.ONGOING
            "completed" -> SAnime.COMPLETED
            "dropped" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
        artist = studios.joinToString { it.name }.ifEmpty { null }
        genre = genres.joinToString { it.name }.ifEmpty { null }
        description = buildString {
            plot?.let(::append)
            type?.let { append("\n\nTipo: ${it.uppercase()}") }
            if (season != null || year != null) {
                append("\nStagione: ${listOfNotNull(season, year).joinToString(" ")}")
            }
            append("\nValutazione: ★${score ?: "-"}")
        }.trim()
    }
}

@Serializable
class InertiaDto<T>(val props: T)

@Serializable
class AnimeProps(
    val anime: AnimeDto,
    val loadedEpisodes: EpisodesDto,
)

@Serializable
class RelatedProps(
    val relatedAnimes: List<AnimeDto>,
    val recommendedAnimes: List<AnimeDto>,
)

@Serializable
class TimestampDto(
    val kind: String,
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double,
)

@Serializable
class EmbedProps(val embedUrl: String)

@Serializable
class EpisodesDto(
    val episodes: List<EpisodeDto>,
    val start: Int,
    @SerialName("tab_size") private val tabSize: Int,
    private val total: Int,
    private val offset: Int,
) {
    /** The first episode number of every page; [total] counts the numbers from [offset] on. */
    val pageStarts get() = offset until offset + total step tabSize

    @Serializable
    class EpisodeDto(
        val number: String,
        val videos: List<VideoDto>,
    ) {
        @Serializable
        class VideoDto(
            val id: Int,
            val language: String,
        )
    }
}
