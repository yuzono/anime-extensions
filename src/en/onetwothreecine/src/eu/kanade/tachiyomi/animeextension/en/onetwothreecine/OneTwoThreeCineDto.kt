package eu.kanade.tachiyomi.animeextension.en.onetwothreecine

import kotlinx.serialization.Serializable

// ==================== Generic enc-dec Response ====================

@Serializable
data class ResultResponse(
    val result: String = "",
)

@Serializable
data class DecryptedIframeResponse(
    val result: DecryptedUrl = DecryptedUrl(),
)

@Serializable
data class DecryptedUrl(
    val url: String = "",
)

// ==================== Episode List Response ====================
// GET /api/v1/titles/{id}/episodes?_={enc}

@Serializable
data class EpisodesResponse(
    val status: String = "",
    val result: EpisodesResult = EpisodesResult(),
)

@Serializable
data class EpisodesResult(
    val title: EpisodeTitle = EpisodeTitle(),
    val seasons: List<Season> = emptyList(),
)

@Serializable
data class EpisodeTitle(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val uri: String = "",
)

@Serializable
data class Season(
    val id: Int = 0,
    val number: Int = 0,
    val name: String = "",
    val air_date: String? = null,
    val episodes: List<EpisodeItem> = emptyList(),
)

@Serializable
data class EpisodeItem(
    val number: Int = 0,
    val id: String = "",
    val slug: String = "",
    val detail_name: String? = null,
    val detail_released_at: String? = null,
    val uri: String = "",
)

// ==================== Episode Servers Response ====================
// GET /api/v1/episodes/{episodeId}?_={enc}

@Serializable
data class EpisodeServersResponse(
    val status: String = "",
    val result: EpisodeServersResult = EpisodeServersResult(),
)

@Serializable
data class EpisodeServersResult(
    val id: String = "",
    val number: Int = 0,
    val slug: String = "",
    val links: List<ServerLink> = emptyList(),
)

@Serializable
data class ServerLink(
    val server_id: Int = 0,
    val id: String = "",
    val name: String = "",
)

// ==================== Link Response ====================
// GET /api/v1/links/{id}?_={enc}

@Serializable
data class LinkResponse(
    val status: String = "",
    val result: String = "",
)
