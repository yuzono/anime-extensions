package eu.kanade.tachiyomi.animeextension.en.senshi

import android.util.LruCache
import eu.kanade.tachiyomi.animeextension.en.senshi.Senshi.Companion.parseStatus
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Endpoint → DTO map:
 *
 *   POST /anime/filter                        -> FilterResponseDto
 *   GET  /anime/{public_id}                   -> SenshiAnimeDto (adds anilist_id)
 *   GET  /anime/{malId}/related               -> List<SenshiAnimeDto>
 *   GET  /anime/{public_id}/recommended       -> List<SenshiAnimeDto>
 *   GET  /episodes/{malId}                    -> List<EpisodeDto>          (bare array)
 *   GET  /episode-embeds/{malId}/{epNumber}   -> List<EpisodeEmbedDto>     (bare array)
 *   GET  https://s.vidcloud.se/_v1/sources?id={remote_source_id} ->
 *   List<VidcloudEntryDto>    (bare array)
 *
 * Deliberately unmodeled: created_at/last_viewed_at (site DB stamps),
 * views_day/week/month, scored_by, version (telemetry), rating and producers
 * (always null in captures).
 */

// =============================== Catalog ==================================
@Serializable
class FilterResponseDto(
    val data: List<SenshiAnimeDto> = emptyList(),
    val total: Int = 0,
)

/**
 * Flat anime object shared by filter/details/related/recommended.
 *
 * `id` is the MyAnimeList anime ID (verified: Cowboy Bebop = 1, Dragon Ball
 * = 223) — it drives /episodes/{id}, /episode-embeds/{id}/{ep} and
 * /anime/{id}/related. `public_id` is the short URL code behind
 * /anime/{public_id} and /watch/{public_id}/{ep}.
 */
@Serializable
class SenshiAnimeDto(
    val id: Int,
    @SerialName("public_id") val publicId: String,
    @SerialName("anime_picture") val animePicture: String? = null,
    val trailer: String? = null,
    val title: String? = null,
    @SerialName("title_english") val titleEnglish: String? = null,
    val synonyms: String? = null,
    val type: String? = null,
    @SerialName("ani_source") val aniSource: String? = null,
    @SerialName("ani_episodes") val aniEpisodes: String? = null,
    @SerialName("ani_status") val aniStatus: String? = null,
    @SerialName("airing_date") val airingDate: String? = null,
    val duration: String? = null,
    val score: Double? = null,
    val genres: String? = null,
    val studios: String? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    @SerialName("anilist_id") val anilistId: Int? = null,
    @SerialName("sub_count") val subCount: Int = 0,
    @SerialName("dub_count") val dubCount: Int = 0,
    @SerialName("ani_description") val aniDescription: String? = null,
    @SerialName("ani_season") val aniSeason: String? = null,
    @SerialName("ani_year") val aniYear: Int? = null,
) {
    fun preferredTitle(language: String): String? {
        val preferred = when (language) {
            "english" -> titleEnglish
            else -> title
        }?.takeIf(String::isNotBlank)
        return preferred ?: title?.takeIf(String::isNotBlank)
            ?: titleEnglish?.takeIf(String::isNotBlank)
    }

    fun toSAnime(titleLanguage: String, baseUrl: String): SAnime? {
        val display = preferredTitle(titleLanguage) ?: return null
        return SAnime.create().apply {
            url = publicId // /watch/{public_id}/{ep}
            title = display
            thumbnail_url = animePicture?.takeIf(String::isNotBlank)
                ?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            genre = genres?.takeIf(String::isNotBlank)
            status = parseStatus(aniStatus)
            description = aniDescription?.takeIf(String::isNotBlank)
        }
    }
}

// ============================== Episodes ==================================
// Episodes — GET /episodes/{malId}
@Serializable
class EpisodeDto(
    val id: Int? = null,
    @SerialName("ep_id") val epId: Int,
    @SerialName("mal_id") val malId: Int? = null,
    @SerialName("ep_title") val epTitle: String = "",
    @SerialName("ep_filler") val epFiller: Boolean = false,
    @SerialName("ep_recap") val epRecap: Boolean = false,
    @SerialName("ep_thumbnail") val epThumbnail: String? = null,
    @SerialName("intro_start") val introStart: Double? = null,
    @SerialName("intro_end") val introEnd: Double? = null,
    @SerialName("outro_start") val outroStart: Double? = null,
    @SerialName("outro_end") val outroEnd: Double? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

// =============================== Hosters ==================================
// GET /episode-embeds/{malId}/{epNumber}
@Serializable
class EpisodeEmbedDto(
    val id: Int? = null, // embed row id — unused
    @SerialName("public_id") val publicId: String? = null, // uuid behind /stream/{uuid}/... — SPA iframe route, not a manifest
    @SerialName("remote_source_id") val remoteSourceId: Int?, // Vidcloud source id
    val url: String? = null, // "https://senshi.to/stream/..." — unused
    val status: String? = null,
    @SerialName("intro_start_ms") val introStartMs: Long? = null,
    @SerialName("intro_end_ms") val introEndMs: Long? = null,
    @SerialName("outro_start_ms") val outroStartMs: Long? = null,
    @SerialName("outro_end_ms") val outroEndMs: Long? = null,
)

// =============================== Streams ==================================
@Serializable
class VidcloudEntryDto(
    val source: VidcloudSourceDto? = null,
    val tracks: List<VidcloudTrackDto> = emptyList(),
)

@Serializable
class VidcloudSourceDto(
    val src: String? = null, // pre-signed EM3U8v1 master manifest (bcdn1/bcdn2 hosts rotate)
    val quality: String? = null,
    val audio: String? = null,
)

@Serializable
class VidcloudTrackDto(
    val url: String? = null,
    @SerialName("vtt_url") val vttUrl: String? = null,
    val label: String? = null,
    val default: Boolean? = null,
)

// ============================= Skip Times =================================
class SkipTimes(
    val introStart: Double? = null,
    val introEnd: Double? = null,
    val outroStart: Double? = null,
    val outroEnd: Double? = null,
)

/** Keyed by "{publicId}/{epId}" — written in getEpisodeList, read in getHosterList. */
val skipTimesCache by lazy { LruCache<String, SkipTimes>(64) }
