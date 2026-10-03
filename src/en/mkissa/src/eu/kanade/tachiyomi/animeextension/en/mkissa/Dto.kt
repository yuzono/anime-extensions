package eu.kanade.tachiyomi.animeextension.en.mkissa

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class PopularResult(
    val data: PopularResultData,
) {
    @Serializable
    class PopularResultData(
        val queryPopular: QueryPopularData,
    ) {
        @Serializable
        class QueryPopularData(
            val recommendations: List<Recommendation>,
        ) {
            @Serializable
            class Recommendation(
                val anyCard: ShowCard? = null,
            )
        }
    }
}

@Serializable
class SearchResult(
    val data: SearchResultData,
) {
    @Serializable
    class SearchResultData(
        val shows: SearchResultShows,
    ) {
        @Serializable
        class SearchResultShows(
            val edges: List<ShowCard>,
        )
    }
}

@Serializable
class ShowCard(
    @SerialName("_id") val id: String,
    val name: String,
    val thumbnail: String? = null,
    val englishName: String? = null,
    val nativeName: String? = null,
    val slugTime: String? = null,
)

@Serializable
class DetailsResult(
    val data: DataShow,
) {
    @Serializable
    class DataShow(
        val show: SeriesShows,
    ) {
        @Serializable
        class SeriesShows(
            val genres: List<String>? = null,
            val studios: List<String>? = null,
            val season: AirSeason? = null,
            val status: String? = null,
            val score: Float? = null,
            val type: String? = null,
            val description: String? = null,
        ) {
            @Serializable
            class AirSeason(
                val quarter: String,
                val year: Int,
            )
        }
    }
}

@Serializable
class SeriesResult(
    val data: DataShow,
) {
    @Serializable
    class DataShow(
        val show: SeriesShows,
    ) {
        @Serializable
        class SeriesShows(
            @SerialName("_id") val id: String,
            val availableEpisodesDetail: AvailableEps,
        ) {
            @Serializable
            class AvailableEps(
                val sub: List<String>? = null,
                val dub: List<String>? = null,
            )
        }
    }
}

@Serializable
class EpisodeResult(
    val data: DataEpisode,
) {
    @Serializable
    class DataEpisode(
        val episode: Episode? = null,
    )
}

@Serializable
class Episode(
    val sourceUrls: List<SourceUrl>,
) {
    @Serializable
    class SourceUrl(
        val sourceUrl: String,
        val type: String,
        val sourceName: String,
        val priority: Float = 0F,
    )
}

@Serializable
class EncryptedEpisodeResult(
    val data: EncryptedData,
) {
    @Serializable
    class EncryptedData(
        val tobeparsed: String? = null,
    )
}

@Serializable
class DecryptedEpisodeResult(
    val episode: Episode? = null,
)

// GraphQL error envelope. The streams API returns `AA_CRYPTO_*` codes (e.g.
// AA_CRYPTO_STALE when the epoch has rotated) instead of an encrypted payload.
@Serializable
class AaApiError(
    val errors: List<GraphQlError>? = null,
) {
    @Serializable
    class GraphQlError(
        val message: String? = null,
        val extensions: Extensions? = null,
    ) {
        @Serializable
        class Extensions(
            val code: String? = null,
        )
    }
}

// Response of `/client-crypto/v1/bootstrap`. `k` echoes back the content lane the partB is
// scoped to; a mismatch means the server answered for a different lane than we asked for.
// `switchAt` is the epoch boundary the server rotates keys at; material must be refreshed then.
@Serializable
class AaCryptoBootstrap(
    val epoch: Long,
    val partB: String,
    val k: String? = null,
    val switchAt: Long? = null,
)

@Serializable
class AaReqPayload(
    private val v: Int,
    private val ts: Long,
    private val epoch: Long,
    private val buildId: String,
    private val qh: String,
    private val k: String,
)

// Stored as `SEpisode.url`, so the shape must stay stable for existing library entries.
@Serializable
class EpisodeVariables(
    val variables: Variables,
) {
    @Serializable
    class Variables(
        val showId: String,
        val translationType: String,
        val episodeString: String,
    )
}

// ============================== Requests ==============================

@Serializable
class PopularVariables(
    private val type: String,
    private val size: Int,
    private val dateRange: Int,
    private val page: Int,
)

@Serializable
class SearchVariables(
    private val search: SearchInput,
    private val limit: Int,
    private val page: Int,
    private val translationType: String,
    private val countryOrigin: String? = null,
)

@Serializable
class SearchInput(
    private val allowAdult: Boolean,
    private val allowUnknown: Boolean,
    private val query: String? = null,
    private val sortBy: String? = null,
    private val season: String? = null,
    private val year: Int? = null,
    private val genres: List<String>? = null,
    private val excludeGenres: List<String>? = null,
    private val types: List<String>? = null,
)

@Serializable
class ShowIdVariables(
    @SerialName("_id") private val id: String,
)

@Serializable
class StreamExtensions(
    private val persistedQuery: PersistedQuery,
    private val k: String,
    private val aaReq: String,
) {
    @Serializable
    class PersistedQuery(
        private val version: Int,
        private val sha256Hash: String,
    )
}

// ============================== Hosters ===============================

@Serializable
class HosterData(
    val url: String,
    val extractor: String,
    val serverKey: String,
    val priority: Float,
)
