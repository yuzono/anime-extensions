package eu.kanade.tachiyomi.animeextension.es.tokianime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class CatalogResponse(
    val items: List<CatalogAnime>,
    val total: Int,
    @SerialName("nextCursor") val nextCursor: String? = null,
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
    val studio: String? = null,
    val color: String? = null,
    val likes: Int? = null,
    val views: Int? = null,
    val votes: Int? = null,
    val isAdult: Boolean = false,
    val sources: List<AnimeSource> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("coverImage") val coverImage: String? = null,
    @SerialName("bannerImage") val bannerImage: String? = null,
    @SerialName("logoImage") val logoImage: String? = null,
    @SerialName("episodesTotal") val episodesTotal: Int? = null,
    @SerialName("episodesAvailable") val episodesAvailable: Int? = null,
    @SerialName("titleNative") val titleNative: String? = null,
    @SerialName("titleEnglish") val titleEnglish: String? = null,
    @SerialName("seasonYear") val seasonYear: Int? = null,
    @SerialName("siteRating") val siteRating: Double? = null,
    val recommendations: List<Int> = emptyList(),
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
    val url: String,
    val site: String,
    val slug: String,
)

@Serializable
class FacetsResponse(
    val genres: List<String> = emptyList(),
)

@Serializable
class RankedServersData(
    @SerialName("rankedServers") val rankedServers: List<RankedServer> = emptyList(),
)

@Serializable
class RankedServer(
    @SerialName("sourceId") val sourceId: String,
    val lang: String,
    val site: String? = null,
    val quality: String? = null,
    val tier: String? = null,
    @SerialName("play") val play: PlayerSource? = null,
)

@Serializable
class PlayerSource(
    val src: String,
    val kind: String,
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
    val overview: String? = null,
)

@Serializable
class RecommendationItem(
    val id: Int,
    val slug: String,
    val title: String,
    @SerialName("coverImage") val coverImage: String? = null,
    @SerialName("synopsis") val synopsis: String? = null,
    val status: String? = null,
    val format: String? = null,
    val genres: List<String> = emptyList(),
)
