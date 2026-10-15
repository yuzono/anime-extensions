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
class SourceUrl(
    val sourceUrl: String,
    val type: String,
    val sourceName: String,
    val priority: Float = 0F,
)

// GraphQL error envelope; a throttled or failed query carries its reason here.
@Serializable
class ApiError(
    val errors: List<GraphQlError>? = null,
) {
    @Serializable
    class GraphQlError(
        val message: String? = null,
    )
}

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

// ============================== Hosters ===============================

@Serializable
class HosterData(
    val url: String,
    val extractor: String,
    val serverKey: String,
    val priority: Float,
)
