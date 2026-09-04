package eu.kanade.tachiyomi.animeextension.es.tokianime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class CatalogResponse(
    val items: List<CatalogAnime>,
    private val total: Int,
    @SerialName("nextCursor") private val nextCursor: String? = null,
)

@Serializable
class CatalogAnime(
    val id: Int,
    val slug: String,
    val title: String,
    val synopsis: String? = null,
    val status: String,
    val format: String,
    val genres: List<String> = emptyList(),
    private val studio: String? = null,
    private val color: String? = null,
    private val likes: Int? = null,
    private val views: Int? = null,
    private val votes: Int? = null,
    private val isAdult: Boolean = false,
    private val sources: List<AnimeSource> = emptyList(),
    private val tags: List<String> = emptyList(),
    @SerialName("coverImage") val coverImage: String? = null,
    @SerialName("bannerImage") private val bannerImage: String? = null,
    @SerialName("logoImage") private val logoImage: String? = null,
    @SerialName("episodesTotal") private val episodesTotal: Int? = null,
    @SerialName("episodesAvailable") private val episodesAvailable: Int? = null,
    @SerialName("titleNative") val titleNative: String? = null,
    @SerialName("titleEnglish") private val titleEnglish: String? = null,
    @SerialName("seasonYear") val seasonYear: Int? = null,
    @SerialName("siteRating") val siteRating: Double? = null,
    private val recommendations: List<Int> = emptyList(),
) {
    fun toSAnime() = eu.kanade.tachiyomi.animesource.model.SAnime.create().apply {
        url = "/anime/$slug"
        title = this@CatalogAnime.title
        thumbnail_url = this@CatalogAnime.coverImage
        genre = this@CatalogAnime.genres.joinToString()
        status = parseStatus(this@CatalogAnime.status)
        description = buildString {
            this@CatalogAnime.synopsis?.let { append(it) }
            this@CatalogAnime.titleNative?.let { append("\n\nTítulo original: $it") }
            this@CatalogAnime.seasonYear?.let { append("\nAño: $it") }
            this@CatalogAnime.siteRating?.let { append("\nRating: $it") }
        }
        initialized = true
    }

    companion object {
        fun parseStatus(status: String) = when (status) {
            "RELEASING" -> eu.kanade.tachiyomi.animesource.model.SAnime.ONGOING
            "FINISHED" -> eu.kanade.tachiyomi.animesource.model.SAnime.COMPLETED
            "HIATUS" -> eu.kanade.tachiyomi.animesource.model.SAnime.ON_HIATUS
            else -> eu.kanade.tachiyomi.animesource.model.SAnime.UNKNOWN
        }
    }
}

@Serializable
class AnimeSource(
    private val url: String,
    private val site: String,
    private val slug: String,
)

@Serializable
class RankedServersData(
    @SerialName("rankedServers") val rankedServers: List<RankedServer> = emptyList(),
)

@Serializable
class RankedServer(
    @SerialName("sourceId") private val sourceId: String,
    val lang: String,
    private val site: String? = null,
    val quality: String? = null,
    private val tier: String? = null,
    @SerialName("play") val play: PlayerSource? = null,
)

@Serializable
class PlayerSource(
    val src: String,
)

@Serializable
class EpisodesResponse(
    val slug: String,
    val available: Int? = null,
    @SerialName("withVideo") val withVideo: List<Int> = emptyList(),
    val meta: Map<String, EpisodeMeta> = emptyMap(),
)

@Serializable
class EpisodeMeta(
    val title: String? = null,
)
