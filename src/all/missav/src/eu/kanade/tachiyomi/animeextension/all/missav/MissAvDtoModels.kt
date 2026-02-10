package eu.kanade.tachiyomi.animeextension.all.missav

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RelatedResponse(
    val json: RecommendationsResponse,
) {
    fun toAnimeList(): List<SAnime> = json.toAnimePage().animes
}

@Serializable
data class RecommendationsResponse(
    val recommId: String,
    @SerialName("recomms")
    val recommendations: List<Recommendation>,
    val numberNextRecommsCalls: Int,
) {
    fun toAnimePage(): AnimesPage = AnimesPage(
        recommendations.mapNotNull { it.toSAnime() },
        hasNextPage = recommendations.size >= MissAvApi.RESULT_COUNT,
    )
}

@Serializable
data class Recommendation(
    val id: String,
    @SerialName("values")
    val videoInfo: VideoInfo,
) {
    fun toSAnime(): SAnime? {
        if (videoInfo.dm == null || videoInfo.titleEn == null) return null
        return SAnime.create().apply {
            url = if (videoInfo.dm == 0) "/en/$id" else "/dm${videoInfo.dm}/en/$id"
            title = "${stripID().uppercase()} ${videoInfo.titleEn}"
            thumbnail_url = "https://fourhoi.com/${stripID()}/cover-t.jpg"
        }
    }

    private fun stripID(): String = id.lowercase().replace(STRIP_SUB_REGEX, "")
}

private val STRIP_SUB_REGEX by lazy { Regex("""-uncensored-leak|-chinese-subtitle|-english-subtitle""") }

@Serializable
data class VideoInfo(
    val dm: Int?,
    @SerialName("title_en")
    val titleEn: String?,
)
