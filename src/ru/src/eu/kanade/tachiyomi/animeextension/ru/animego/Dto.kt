package eu.kanade.tachiyomi.animeextension.ru.animego

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import java.util.Locale

internal const val EPISODE_SEASON_PARAM = "animego-season"
internal const val EPISODE_NUMBER_PARAM = "animego-episode"

internal class PagePlayerState(
    val cdn: CdnConfig?,
    val kodik: KodikConfig?,
    val alloha: AllohaConfig?,
)

internal class CdnConfig(
    val mali: String,
    val pub: String,
)

internal class KodikConfig(
    val params: String,
)

internal class AllohaConfig(
    val params: String,
)

internal class EpisodeKey(
    val season: Int,
    val episode: Int,
)

internal class EpisodeAvailability(
    val key: EpisodeKey,
    val cdnDubbings: MutableSet<String> = linkedSetOf(),
    val kodikDubbings: MutableSet<String> = linkedSetOf(),
    val allohaDubbings: MutableSet<String> = linkedSetOf(),
)

internal class VideoCandidate(
    val video: Video,
    val metadata: PlaybackMetadata,
    val quality: String,
)

@Serializable
internal class PlaybackMetadata(
    val playerId: String,
    val playerLabel: String,
    val dubbingId: String,
    val dubbingLabel: String,
    val sortOrder: Int,
)

@Serializable
internal class CdnPlaylistResponse(
    val items: List<CdnPlaylistItem> = emptyList(),
)

@Serializable
internal class CdnPlaylistItem(
    val vkId: String,
    private val voiceStudio: String = "",
    private val voiceType: String = "",
    val season: Int = 1,
    val episode: Int = 1,
) {
    fun voiceStudioLabel(): String = voiceStudio
}

@Serializable
internal class CdnVideoResponse(
    val sources: CdnVideoSources,
)

@Serializable
internal class CdnVideoSources(
    val hlsUrl: String = "",
    val dashUrl: String = "",
    val mpegTinyUrl: String = "",
    val mpegLowestUrl: String = "",
    val mpegLowUrl: String = "",
    val mpegMediumUrl: String = "",
    val mpegHighUrl: String = "",
    val mpegFullHdUrl: String = "",
)

@Serializable
internal class KodikIframeResponse(
    val success: Boolean = false,
    val data: String = "",
)

internal class KodikState(
    val urlParams: String,
    val seasons: List<KodikSeasonOption>,
    val episodesBySeason: Map<Int, List<KodikEpisodeOption>>,
    val translations: List<KodikTranslationOption>,
)

internal class AllohaState(
    val refererUrl: String,
    val pageReferer: String,
    val tracksByEpisode: Map<EpisodeKey, List<AllohaTrack>>,
    val visibleSeasonIds: Set<Int>,
    val bnsiMovieId: String = "",
    val borthSeed: String = "",
)

internal class AllohaTrack(
    val videoId: String,
    val season: Int,
    val episode: Int,
    val translationId: Int,
    val translationLabel: String,
)

internal class ParsedAllohaTrack(
    val season: Int,
    val episode: Int,
    val videoId: String,
    val translationId: Int,
    val translationLabel: String,
)

internal class AllohaRequestFullContext(
    val season: Int,
    val episode: Int,
    val voiceId: Int?,
)

internal class KodikSeasonOption(
    val season: Int,
    val serialId: String,
    val serialHash: String,
)

internal class KodikEpisodeOption(
    val number: Int,
    val id: String,
    val hash: String,
)

internal class KodikTranslationOption(
    val translationId: Int,
    val title: String,
    val mediaId: String,
    val mediaHash: String,
    val mediaType: String,
    val episodeCount: Int,
)

internal class KodikVideoInfo(
    val type: String,
    val id: String,
    val hash: String,
)

@Serializable
internal class KodikForm(
    val d: String = "",
    @SerialName("d_sign") val dSign: String = "",
    val pd: String = "",
    @SerialName("pd_sign") val pdSign: String = "",
    val ref: String = "",
    @SerialName("ref_sign") val refSign: String = "",
)

@Serializable
internal class KodikFtorResponse(
    val links: Map<String, List<KodikEncodedLink>> = emptyMap(),
)

@Serializable
internal class KodikEncodedLink(
    private val src: String,
) {
    fun src(): String = src
}

internal fun Map<String, List<KodikEncodedLink>>.qualityEntries(): List<Pair<String, String>> = entries
    .mapNotNull { (quality, links) ->
        val normalizedQuality = quality.toIntOrNull()?.let { "${it}p" } ?: quality
        links.firstOrNull()?.src()?.takeIf(String::isNotBlank)?.let { normalizedQuality to it }
    }
    .sortedByDescending { (quality, _) ->
        quality.removeSuffix("p").toIntOrNull() ?: 0
    }

internal fun CdnPlaylistItem.displayDubbing(): String = voiceStudioLabel().ifBlank { "Субтитры" }

internal fun Map<String, List<KodikEncodedLink>>.qualityMap(): Map<String, String> = qualityEntries().associate { it.first to it.second }

internal fun CdnVideoSources.qualityMap(): Map<String, String> = buildMap {
    mpegFullHdUrl.takeIf(String::isNotBlank)?.let { put("1080p", it) }
    mpegHighUrl.takeIf(String::isNotBlank)?.let { put("720p", it) }
    mpegMediumUrl.takeIf(String::isNotBlank)?.let { put("480p", it) }
    mpegLowUrl.takeIf(String::isNotBlank)?.let { put("360p", it) }
    mpegLowestUrl.takeIf(String::isNotBlank)?.let { put("240p", it) }
    mpegTinyUrl.takeIf(String::isNotBlank)?.let { put("144p", it) }
}

internal fun KodikTranslationOption.supportsEpisode(episode: Int): Boolean = episodeCount <= 0 || episode <= episodeCount

internal fun EpisodeAvailability.toSEpisode(animeUrl: String): SEpisode {
    val episode = SEpisode.create()
    episode.name = key.buildEpisodeName()
    episode.episode_number = key.episode.toFloat()
    episode.scanlator = buildScanlatorLabel()
    episode.date_upload = 0L
    val url = animeUrl.toHttpUrl().newBuilder()
        .addQueryParameter(EPISODE_SEASON_PARAM, key.season.toString())
        .addQueryParameter(EPISODE_NUMBER_PARAM, key.episode.toString())
        .build()
    episode.url = url.encodedPath + url.query?.let { "?$it" }.orEmpty()
    return episode
}

internal fun EpisodeKey.buildEpisodeName(): String = if (season > 1) {
    "Сезон $season • Серия $episode"
} else {
    "Серия $episode"
}

internal fun EpisodeAvailability.buildScanlatorLabel(): String {
    val sections = buildList {
        if (cdnDubbings.isNotEmpty()) {
            add("CDN: ${cdnDubbings.sorted().joinToString()}")
        }
        if (kodikDubbings.isNotEmpty()) {
            add("Kodik: ${kodikDubbings.sorted().joinToString()}")
        }
        if (allohaDubbings.isNotEmpty()) {
            add("Alloha: ${allohaDubbings.sorted().joinToString()}")
        }
    }
    return sections.joinToString(" | ")
}

internal fun Element.toKodikEpisodeOption(): KodikEpisodeOption? {
    val number = attr("value").toIntOrNull() ?: return null
    val id = attr("data-id")
    val hash = attr("data-hash")
    if (id.isBlank() || hash.isBlank()) return null
    return KodikEpisodeOption(number = number, id = id, hash = hash)
}

internal fun parseAnimeStatus(rawStatus: String): Int {
    val normalized = rawStatus.lowercase(Locale.ROOT)
    return when {
        normalized.contains("онгоинг") || normalized.contains("ongoing") -> SAnime.ONGOING
        normalized.contains("анонс") || normalized.contains("announce") -> SAnime.UNKNOWN
        normalized.contains("вышел") || normalized.contains("released") ||
            normalized.contains("заверш") -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }
}
