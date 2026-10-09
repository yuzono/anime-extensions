package eu.kanade.tachiyomi.animeextension.en.anikage

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
data class BrowseResponseDto(
    val data: List<AnimeItemDto> = emptyList(),
    val hasNext: Boolean = false,
)

@Serializable
data class DetailsResponseDto(
    val anime: AnimeItemDto? = null,
)

@Serializable
data class TitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
    val userPreferred: String? = null,
) {
    fun best(): String = english ?: romaji ?: userPreferred ?: native ?: ""
}

@Serializable
data class CoverDto(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
) {
    fun best(): String? = extraLarge ?: large ?: medium
}

@Serializable
data class StudioDto(
    val name: String? = null,
)

@Serializable
data class AnimeItemDto(
    val slug: String? = null,
    val title: TitleDto? = null,
    val coverImage: CoverDto? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val studios: List<StudioDto>? = null,
    val status: String? = null,
    val format: String? = null,
    val year: Int? = null,
    val season: String? = null,
    val totalEpisodes: Int? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        title = this@AnimeItemDto.title?.best().orEmpty()
        url = "/anime/info/${slug.orEmpty()}"
        thumbnail_url = coverImage?.best() ?: bannerImage
        description = buildDescription()
        genre = genres?.joinToString()
        author = studios?.mapNotNull { it.name }?.joinToString()?.takeIf { it.isNotBlank() }
        status = when (this@AnimeItemDto.status?.uppercase()) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "NOT_YET_RELEASED" -> SAnime.LICENSED
            "HIATUS" -> SAnime.ON_HIATUS
            "CANCELLED" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
        fetch_type = FetchType.Episodes
    }

    private fun buildDescription(): String {
        val plot = description?.replace(Regex("<br\\s*/?>"), "\n")?.replace(Regex("<[^>]+>"), "")?.trim()
        val meta = buildList {
            format?.let { add("Format: $it") }
            year?.let { add("Year: $it") }
            season?.let { add("Season: ${it.lowercase().replaceFirstChar { c -> c.uppercase() }}") }
            totalEpisodes?.let { add("Episodes: $it") }
        }.joinToString(" • ")
        return listOf(plot, meta).filter { !it.isNullOrBlank() }.joinToString("\n\n")
    }
}

@Serializable
data class EpisodeDto(
    val number: Float? = null,
    val title: String? = null,
    val seasonNumber: Int? = null,
    val image: String? = null,
    val description: String? = null,
    val airDate: String? = null,
    val isFiller: Boolean? = null,
) {
    fun toSEpisode(slug: String): SEpisode = SEpisode.create().apply {
        val num = number ?: 1f
        val epLabel = if (num == num.toInt().toFloat()) num.toInt().toString() else num.toString()
        val fillerTag = if (isFiller == true) " (Filler)" else ""
        name = "Episode $epLabel${title?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""}$fillerTag"
        episode_number = num
        url = "$slug#ep=$epLabel"
        preview_url = image
        summary = description
        date_upload = parseDate(airDate)
    }

    private fun parseDate(date: String?): Long {
        date ?: return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(date)?.time ?: 0L
        }.getOrDefault(0L)
    }
}

@Serializable
data class ServersResponseDto(
    val servers: List<ServerDto> = emptyList(),
)

@Serializable
data class ServerDto(
    val id: String? = null,
    val subTypes: List<String> = emptyList(),
)

@Serializable
data class SourcesResponseDto(
    val sources: List<SourceItemDto> = emptyList(),
    val subtitles: List<ApiSubtitleDto> = emptyList(),
    val embeds: List<EmbedDto> = emptyList(),
    val embedOptions: List<EmbedOptionDto> = emptyList(),
) {
    private fun labelsByUrl(): Map<String, String> = buildMap {
        embeds.forEach { embed ->
            val url = embed.url ?: return@forEach
            embed.server?.takeIf { it.isNotBlank() }?.let { put(url, it) }
        }
        embedOptions.forEach { option ->
            val url = option.url ?: return@forEach
            option.label?.takeIf { it.isNotBlank() }?.let { put(url, it) }
        }
    }

    private fun captionTracks(): List<ApiSubtitleDto> = subtitles.filter {
        val kind = it.kind?.lowercase()
        kind == null || kind == "captions" || kind == "subtitles"
    }

    fun toStreamEntries(lang: String, refererByHost: Map<String, String>): List<StreamEntry> {
        val labels = labelsByUrl()
        val tracks = captionTracks().mapNotNull { sub ->
            val url = decodeStreamToken(sub.file ?: sub.url)?.url ?: return@mapNotNull null
            SubtitleEntry(url = url, label = sub.label ?: "English")
        }

        return sources.mapNotNull { src ->
            val decoded = decodeStreamToken(src.url)
            val streamUrl = decoded?.url.orEmpty()
            val embedUrl = src.embedUrl?.takeIf { it.startsWith("http") }.orEmpty()
            if (streamUrl.isEmpty() && embedUrl.isEmpty()) return@mapNotNull null

            StreamEntry(
                lang = lang.uppercase(),
                url = streamUrl,
                referer = decoded?.referer?.takeIf { it.isNotEmpty() }
                    ?: refererByHost[streamUrl.hostOrEmpty()].orEmpty(),
                isM3U8 = src.isM3U8 ?: true,
                label = src.embedUrl?.let { labels[it] } ?: src.server ?: src.quality.orEmpty(),
                embedUrl = embedUrl,
                subtitles = tracks,
            )
        }
    }
}

@Serializable
data class ApiSubtitleDto(
    val file: String? = null,
    val url: String? = null,
    val label: String? = null,
    val kind: String? = null,
)

@Serializable
data class EmbedDto(
    val url: String? = null,
    val server: String? = null,
)

@Serializable
data class EmbedOptionDto(
    val url: String? = null,
    val label: String? = null,
)

@Serializable
data class SourceItemDto(
    val embedUrl: String? = null,
    val url: String? = null,
    val quality: String? = null,
    val isM3U8: Boolean? = null,
    val server: String? = null,
)

@Serializable
data class MegaPlaySourcesDto(
    val sources: MegaPlayFileDto? = null,
    val tracks: List<MegaPlayTrackDto> = emptyList(),
)

@Serializable
data class MegaPlayFileDto(
    val file: String? = null,
)

@Serializable
data class MegaPlayTrackDto(
    val file: String? = null,
    val label: String? = null,
    val kind: String? = null,
)

@Serializable
data class StreamEntry(
    val lang: String,
    val url: String,
    val referer: String,
    val isM3U8: Boolean,
    val label: String,
    val embedUrl: String,
    val subtitles: List<SubtitleEntry> = emptyList(),
) {
    fun refererCandidates(): List<String> = buildList {
        if (referer.isNotBlank()) add(referer)
        url.toHttpUrlOrNull()?.let { httpUrl ->
            add("${httpUrl.scheme}://${httpUrl.host}/")
            val apex = httpUrl.host.split('.').takeLast(2).joinToString(".")
            add("${httpUrl.scheme}://$apex/")
        }
        add("")
    }.distinct()
}

@Serializable
data class SubtitleEntry(
    val url: String,
    val label: String,
)

data class DecodedStream(
    val url: String,
    val referer: String,
)

private val TOKEN_KEY = "dj5D455Lzl2LKJXEtFwb5gy2oGFSYPnBKp7PTgFPm6Gn2MGb".toByteArray()
private const val TOKEN_SEPARATOR = '\u0000'

fun decodeStreamToken(token: String?): DecodedStream? {
    if (token.isNullOrBlank()) return null
    val raw = runCatching {
        Base64.decode(token.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null

    val plain = String(
        ByteArray(raw.size) { i -> (raw[i].toInt() xor TOKEN_KEY[i % TOKEN_KEY.size].toInt()).toByte() },
    )
    val parts = plain.split(TOKEN_SEPARATOR)
    val url = parts.firstOrNull()?.trim().orEmpty()
    if (!url.startsWith("http")) return null

    return DecodedStream(url = url, referer = parts.getOrNull(1)?.trim().orEmpty())
}

fun String.hostOrEmpty(): String = toHttpUrlOrNull()?.host.orEmpty()
