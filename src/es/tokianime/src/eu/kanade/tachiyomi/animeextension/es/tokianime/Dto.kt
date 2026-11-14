package eu.kanade.tachiyomi.animeextension.es.tokianime

import kotlinx.serialization.Serializable

@Serializable
class CatalogResponse(
    val items: List<CatalogAnime>,
)

@Serializable
class CatalogAnime(
    val slug: String,
    val title: String,
    val synopsis: String? = null,
    val status: String,
    val genres: List<String> = emptyList(),
    val coverImage: String? = null,
    val titleNative: String? = null,
    val seasonYear: Int? = null,
    val siteRating: Double? = null,
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
class RankedServersData(
    val rankedServers: List<RankedServer> = emptyList(),
)

@Serializable
class RankedServer(
    val lang: String,
    val quality: String? = null,
    val play: PlayerSource? = null,
)

@Serializable
class PlayerSource(
    val src: String,
)

@Serializable
class EpisodesResponse(
    val withVideo: List<Int> = emptyList(),
    val meta: Map<String, EpisodeMeta> = emptyMap(),
)

@Serializable
class EpisodeMeta(
    val title: String? = null,
)
