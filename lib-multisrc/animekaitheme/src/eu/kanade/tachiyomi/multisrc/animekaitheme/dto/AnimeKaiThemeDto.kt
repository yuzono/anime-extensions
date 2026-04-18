package eu.kanade.tachiyomi.multisrc.animekaitheme.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// ========================= Shared DTOs =========================

data class VideoCode(
    val type: String,
    val serverId: String,
    val serverName: String,
)

data class VideoData(
    val iframe: String,
    val serverName: String,
)

// Replaces: animekai.ResultResponse, hianimews.ResultResponse, anigo.AniGoEncryptedResponse
@Serializable
data class ResultResponse(
    val result: String,
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}

@Serializable
data class IframeResponse(
    val result: IframeDto,
)

// {"url":"https:\/\/megaup.site\/e\/...","skip":...}
// Added default values (= null) so this handles AniGo's JSON seamlessly too
@Serializable
data class IframeDto(
    val url: String,
    val skip: SkipDto? = null,
)

// "skip":{"intro":[0,0],"outro":[0,0]}
@Serializable
data class SkipDto(
    val intro: List<Int>? = null,
    val outro: List<Int>? = null,
)

// ========================= AniGo Specific DTOs =========================

@Serializable
data class AniGoEpisodesResponse(
    val status: String,
    val result: AniGoEpisodesResult,
)

@Serializable
data class AniGoEpisodesResult(
    val langs: List<String> = emptyList(),
    val episodeCount: Int = 0,
    val rangedEpisodes: List<AniGoEpisodeRange> = emptyList(),
)

@Serializable
data class AniGoEpisodeRange(
    val label: String = "",
    val episodes: List<AniGoEpisode> = emptyList(),
)

@Serializable
data class AniGoEpisode(
    val number: Int,
    val name: String = "",
    val slug: String = "",
    @SerialName("detail_name") val detailName: String? = null,
    @SerialName("detail_release") val detailRelease: String? = null,
    val langs: Int = 0,
    @SerialName("is_filler") val isFiller: Int = 0,
    val token: String,
)

@Serializable
data class AniGoEpTokenResponse(
    val status: String,
    val result: List<AniGoEpTokenDto>,
)

@Serializable
data class AniGoEpTokenDto(
    val id: String,
    val lang: String,
    val number: Int,
    val links: List<AniGoServerLinkDto>,
)

@Serializable
data class AniGoServerLinkDto(
    val id: String,
    @SerialName("server_id") val serverId: Int,
    @SerialName("server_title") val serverTitle: String,
)

@Serializable
data class AniGoLinkResponse(
    val status: String,
    val result: String,
)
