package eu.kanade.tachiyomi.animeextension.fr.franime

import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

@Serializable
class Anime(
    val id: Long,
    @SerialName("titleO") val titleOriginal: String,
    val title: String,
    @SerialName("titles") val titlesAlt: TitlesAlt,
    val description: String,
    @SerialName("themes") val genres: List<String>,
    @SerialName("affiche") val poster: String,
    val status: String,
    val note: String,
    val nsfw: Boolean,
    val format: String,
    val startDate: String?,
    val updatedDate: Long?,
    @SerialName("source_url") val sourceUrl: String?,
    @SerialName("saisons") val seasons: List<Season>,
)

@Serializable
class TitlesAlt(
    @SerialName("en_us") val enUs: String?,
    @SerialName("en_jp") val enJp: String?,
    @SerialName("ja_jp") val jaJp: String?,
)

@Serializable
class Season(
    val title: String,
    val episodes: List<Episode>,
)

@Serializable
class Episode(
    val title: String?,
    @SerialName("lang") val languages: EpisodeLanguages,
)

@Serializable
class EpisodeLanguages(
    val vf: EpisodeLanguage,
    val vo: EpisodeLanguage,
)

@Serializable
class EpisodeLanguage(
    @SerialName("lecteurs") val players: List<String>,
)

// https://api.franime.fr/api/anime-seasons/{animeId}/{seasonNumber}
@Serializable
class SeasonDetails(
    val kitsuId: Long? = null,
    val episodes: List<EpisodeDetails> = emptyList(),
)

@Serializable
class EpisodeDetails(
    val number: Int? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val airDate: String? = null,
)

fun EpisodeDetails.fillFrom(other: EpisodeDetails) = EpisodeDetails(
    number = number ?: other.number,
    title = title ?: other.title,
    thumbnail = thumbnail ?: other.thumbnail,
    airDate = airDate ?: other.airDate,
)

// https://kitsu.io/api/edge/episodes?filter[mediaId]={kitsuId}
@Serializable
class KitsuEpisodesResponse(
    val data: List<KitsuEpisode> = emptyList(),
)

@Serializable
class KitsuEpisode(
    val attributes: KitsuEpisodeAttributes? = null,
)

@Serializable
class KitsuEpisodeAttributes(
    val number: Int? = null,
    val canonicalTitle: String? = null,
    val thumbnail: KitsuThumbnail? = null,
    val airdate: String? = null,
) {
    fun toEpisodeDetails() = EpisodeDetails(
        number = number,
        title = canonicalTitle?.takeIf { it.isNotBlank() },
        thumbnail = thumbnail?.original ?: thumbnail?.large ?: thumbnail?.medium,
        airDate = airdate,
    )
}

@Serializable
class KitsuThumbnail(
    val original: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

// https://kitsu.io/api/edge/anime/{kitsuId}
@Serializable
class KitsuAnimeResponse(
    val data: KitsuAnimeData? = null,
)

@Serializable
class KitsuAnimeData(
    val attributes: KitsuAnimeAttributes? = null,
)

@Serializable
class KitsuAnimeAttributes(
    @SerialName("posterImage") val posterImage: KitsuPosterImage? = null,
)

@Serializable
class KitsuPosterImage(
    val large: String? = null,
    val medium: String? = null,
    val original: String? = null,
)

// Some poster hosts (nautiljon.com) reject hotlinks with a Referer; route those through
// the site's own image proxy like the site UI does. Kitsu images load fine directly.
fun Anime.posterUrl(baseUrl: String): String? {
    val poster = poster.takeIf { it.isNotEmpty() } ?: return null
    val httpUrl = poster.toHttpUrlOrNull() ?: return baseUrl + poster
    if (httpUrl.host.endsWith("kitsu.app")) return poster
    return "$baseUrl/_next/image?url=" + poster.encodeURL() + "&w=640&q=75"
}

fun Anime.displayTitle(): String = title.ifBlank { titleOriginal }

fun Anime.toSAnime(baseUrl: String) = SAnime.create().apply {
    title = displayTitle()
    thumbnail_url = posterUrl(baseUrl)
    genre = genres.sorted().joinToString()
    status = parseStatus()
    description = generalDescription()
    url = "/anime/${slugOf(titleOriginal).ifBlank { slugOf(title).ifBlank { "anime-$id" } }}?anime_id=$id"
    fetch_type = FetchType.Seasons
    initialized = true
}

fun Anime.parseStatus(): Int = when (status.trim().lowercase()) {
    "en cours" -> SAnime.ONGOING
    "terminé" -> SAnime.COMPLETED
    else -> SAnime.UNKNOWN
}

private val titleRegex = Regex("[^A-Za-z0-9 ]")

private fun slugOf(title: String) = titleRegex.replace(title, "").replace(" ", "-").lowercase()

private fun String.encodeURL() = URLEncoder.encode(this, "UTF-8")

const val UNAVAILABLE = "INDISPONIBLE"

fun Episode.voiceList(): List<String> = buildList {
    if (hasVoice("VOSTFR")) add("VO")
    if (hasVoice("VF")) add("VF")
}

fun Episode.hasVoice(voice: String): Boolean = when (voice) {
    "VOSTFR" -> languages.vo.players.any { !it.equals(UNAVAILABLE, true) }
    "VF" -> languages.vf.players.any { !it.equals(UNAVAILABLE, true) }
    else -> false
}

fun Season.hasPlayers(): Boolean = episodes.any { it.hasPlayers() }

fun Episode.hasPlayers(): Boolean = voiceList().isNotEmpty()

fun Anime.generalDescription(): String = buildString {
    seasons.sumOf { s -> s.episodes.count { it.voiceList().isNotEmpty() } }
        .takeIf { it > 0 }
    append(generalMetadataBlock())
}.trim()

fun Anime.seasonDescription(): String = description.trim()

private fun Anime.generalMetadataBlock(): String = buildString {
    normalizedFormat()?.let { append("Format : ").append(it).append("\n") }
    append("Statut : ").append(statusLabel()).append("\n")
    startDate?.takeIf { it.length >= 4 }?.let { date ->
        append("Sortie : ")
        startSeason()?.let { append(it).append(' ') }
        append(date.substring(0, 4)).append("\n")
    }
    if (note.toFloatOrNull() != null) append("Note : ").append(note).append("\n")
}

private fun Anime.statusLabel(): String = when (status.trim().lowercase()) {
    "en cours" -> "En cours"
    "terminé" -> "Terminé"
    "tba" -> "À venir"
    else -> status.trim()
}

fun Anime.matchesQuery(query: String): Boolean = query.isBlank() || listOf(
    title,
    titleOriginal,
    titlesAlt.enUs,
    titlesAlt.enJp,
    titlesAlt.jaJp,
    slugOf(titleOriginal),
    slugOf(title),
).any { it?.contains(query, true) == true }

fun Anime.matchesFilters(params: SearchParams): Boolean {
    if (params.themes.isNotEmpty() && params.themes.none { it in genres }) return false
    if (params.format != null && normalizedFormat() != params.format) return false
    if (params.status != null && !status.trim().equals(params.status, true)) return false
    if (params.type != null && seasons.none { s -> s.episodes.any { it.hasVoice(params.type) } }) return false
    if (params.year != null && startDate?.startsWith(params.year) != true) return false
    if (params.season != null && startSeason() != params.season) return false
    return true
}

// Maps the catalog's raw format values to the search page's filter labels.
fun Anime.normalizedFormat(): String? = when (format.trim().lowercase()) {
    "tv" -> "Série"
    "film", "movie" -> "Film"
    "ona" -> "ONA"
    "ova", "oav" -> "OVA"
    "special" -> "Special"
    else -> null
}

fun Anime.startSeason(): String? {
    val month = startDate
        ?.takeIf { it.length >= 7 }
        ?.substring(5, 7)
        ?.toIntOrNull()
        ?: return null
    return when (month) {
        12, 1, 2 -> "Hiver"
        in 3..5 -> "Printemps"
        in 6..8 -> "Été"
        in 9..11 -> "Automne"
        else -> null
    }
}
