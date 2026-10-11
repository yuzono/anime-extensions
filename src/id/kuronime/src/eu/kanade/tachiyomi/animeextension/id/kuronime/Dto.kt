package eu.kanade.tachiyomi.animeextension.id.kuronime

import kotlinx.serialization.Serializable

@Serializable
class SourceRequestDto(
    val id: String,
)

@Serializable
class SourceResponseDto(
    val mirror: String,
)

@Serializable
class CryptoDto(
    val ct: String,
    val s: String,
)

@Serializable
class DecryptedEmbedDto(
    val embed: Map<String, Map<String, String?>>? = null,
)
