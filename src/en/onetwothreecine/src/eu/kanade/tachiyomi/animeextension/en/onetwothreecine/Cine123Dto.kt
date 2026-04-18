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
    val rangedEpisodes: List<RangedEpisodes> = emptyList(),
)

@Serializable
data class RangedEpisodes(
    val episodes: List<EpisodeItem> = emptyList(),
)

@Serializable
data class EpisodeItem(
    val number: Int = 0,
    val token: String = "",
    val detailName: String? = null,
    val langs: Int = 0,
    val isFiller: Int = 0,
)

// ==================== Episode Servers Response ====================
// GET /api/v1/episodes/{token}?_={enc}

@Serializable
data class EpisodeServersResponse(
    val status: String = "",
    val result: List<ServerGroup> = emptyList(),
)

@Serializable
data class ServerGroup(
    val lang: String = "",
    val links: List<ServerLink> = emptyList(),
)

@Serializable
data class ServerLink(
    val id: String = "",
    val serverTitle: String = "",
)

// ==================== Link Response ====================
// GET /api/v1/links/{id}?_={enc}

@Serializable
data class LinkResponse(
    val status: String = "",
    val result: String = "",
)

// ==================== RapidShare Responses ====================

@Serializable
data class EncryptedRapidResponse(
    val result: String = "",
)

@Serializable
data class RapidDecryptResponse(
    val status: Int = 0,
    val result: RapidShareResult = RapidShareResult(),
)

@Serializable
data class RapidShareResult(
    val sources: List<RapidShareSource> = emptyList(),
    val tracks: List<RapidShareTrack> = emptyList(),
)

@Serializable
data class RapidShareSource(
    val file: String = "",
)

@Serializable
data class RapidShareTrack(
    val file: String = "",
    val label: String? = null,
    val kind: String = "",
)
