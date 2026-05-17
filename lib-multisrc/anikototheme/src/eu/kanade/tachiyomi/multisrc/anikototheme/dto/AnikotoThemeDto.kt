package eu.kanade.tachiyomi.multisrc.anikototheme.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// ========================= Shared DTOs =========================

@Serializable
data class ResultResponse(
    val result: String,
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}

// ========================= Server List & Embed DTOs =========================

@Serializable
data class ServerResponseDto(
    val result: ServerResultDto,
)

@Serializable
data class ServerResultDto(
    val url: String,
)

// ========================= Video Source DTOs =========================

@Serializable
data class SourceResponseDto(
    val sources: JsonElement,
    val tracks: List<TrackDto>? = null,
    val intro: IntroOutroDto? = null,
    val outro: IntroOutroDto? = null,
    val server: Int? = null,
)

@Serializable
data class TrackDto(
    val file: String,
    val kind: String,
    val label: String = "",
)

@Serializable
data class IntroOutroDto(
    val start: Int,
    val end: Int,
)

// ========================= Mapper API DTOs =========================
// Used for the external mapper.mewcdn.online API (Kiwi-Stream, etc.)

@Serializable
data class MapperServerDto(
    val sub: MapperTypeDto? = null,
    val dub: MapperTypeDto? = null,
)

@Serializable
data class MapperTypeDto(
    val url: String? = null,
    // Download keys are dynamic qualities (e.g., "1080p", "720p"), so parsed as JsonObject
    val download: JsonObject? = null,
)
