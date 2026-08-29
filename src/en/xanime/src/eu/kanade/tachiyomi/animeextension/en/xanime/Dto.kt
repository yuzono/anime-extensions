package eu.kanade.tachiyomi.animeextension.en.xanime

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jsoup.parser.Parser
import kotlin.math.roundToInt

@Serializable
class GraphQlResponse<T>(
    val data: T? = null,
)

@Serializable
class SearchResponse(
    @SerialName("get_q27") val searchData: SearchData? = null,
)

@Serializable
class SearchData(
    val paging: Paging? = null,
    val items: List<SearchItem> = emptyList(),
)

@Serializable
class Paging(
    val page: Int = 1,
    val pages: Int = 0,
    val total: Int = 0,
    val next: Int? = null,
    val prev: Int? = null,
)

@Serializable
class SearchItem(
    val id: String? = null,
    private val data: AnimeData? = null,
) {
    val slug: String? get() = data?.slug

    fun toSAnime(baseUrl: String): SAnime = data?.toSAnime(baseUrl)
        ?: throw Exception("Missing anime data${id?.let { " for id $it" } ?: ""}")

    fun toSAnimeDetails(baseUrl: String): SAnime = data?.toSAnimeDetails(baseUrl) ?: toSAnime(baseUrl)
}

@Serializable
class AnimeData(
    @SerialName("ani_id") private val aniId: String,
    @SerialName("ani_id_mal") private val malId: String? = null,
    @SerialName("al_id") private val alId: String? = null,
    @SerialName("info_title") private val title: String? = null,
    @SerialName("info_slug") val slug: String? = null,
    @SerialName("info_filmdesc") private val description: String? = null,
    @SerialName("info_meta_status") private val status: String? = null,
    @SerialName("info_meta_genre") private val genres: List<String> = emptyList(),
    private val urlCover600: String? = null,
    private val urlCoverOri: String? = null,
    @SerialName("bgimg_url") private val bgImg: String? = null,
    @SerialName("info_meta_studios") private val studios: List<String> = emptyList(),
    @SerialName("info_meta_season") private val season: String? = null,
    @SerialName("info_meta_year") private val year: String? = null,
    @SerialName("info_meta_duration") private val duration: String? = null,
    @SerialName("info_meta_rating") private val rating: String? = null,
    @SerialName("info_meta_scores") private val score: Int? = null,
    @SerialName("info_meta_type") private val type: List<String> = emptyList(),
    @SerialName("info_meta_dateAiredBegin") private val dateAiredBegin: String? = null,
    @SerialName("info_meta_dateAiredEnd") private val dateAiredEnd: String? = null,
    @SerialName("info_alternative_titles") private val alternativeTitles: List<AlternativeTitle>? = null,
) {
    fun toSAnime(baseUrl: String) = SAnime.create().apply {
        url = aniId
        title = this@AnimeData.title?.takeIf { it.isNotBlank() }
            ?: throw Exception("Missing anime title${url.takeIf { it.isNotBlank() }?.let { " for id $it" } ?: ""}")

        thumbnail_url = (urlCover600 ?: urlCoverOri ?: bgImg)?.let {
            if (it.startsWith("http")) it else baseUrl + it
        }
    }

    fun toSAnimeDetails(baseUrl: String): SAnime = toSAnime(baseUrl).apply {
        description = buildDetailDescription()
        author = studios.joinToString(", ") { studio ->
            studio.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        status = mappedStatus()
        genre = genres.map { it.normalizeGenre() }.sorted().joinToString()
    }

    private fun buildDetailDescription(): String = buildString {
        score?.let { s ->
            fancyScore(s).takeIf { it.isNotEmpty() }?.let { append("$it\n\n") }
        }
        description?.takeIf { it.isNotBlank() }?.let { raw ->
            append("${Parser.unescapeEntities(raw, false)}\n\n")
        }

        malId?.takeIf { it.isNotBlank() }?.let {
            append("**[MyAnimeList](https://myanimelist.net/anime/$it)**\n")
        }
        alId?.takeIf { it.isNotBlank() }?.let {
            append("**[AniList](https://anilist.co/anime/$it)**\n")
        }
        if (malId != null || alId != null) append("\n")

        alternativeTitles?.takeIf { it.isNotEmpty() }?.let { titles ->
            append("**Alternative Titles:**\n")
            titles.forEach { alt ->
                alt.title?.takeIf { it.isNotBlank() }?.let { append("- $it\n") }
            }
            append("\n")
        }

        type.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { append("**Type:** $it\n") }
        season?.takeIf { it.isNotBlank() }?.let { append("**Season:** $it\n") }
        year?.takeIf { it.isNotBlank() }?.let { append("**Year:** $it\n") }
        duration?.takeIf { it.isNotBlank() }?.let { append("**Duration:** $it\n") }
        rating?.takeIf { it.isNotBlank() }?.let { append("**Rating:** $it\n") }

        val airedBegin = dateAiredBegin?.takeIf { it.isNotBlank() }
        val airedEnd = dateAiredEnd?.takeIf { it.isNotBlank() }
        if (airedBegin != null || airedEnd != null) {
            val aired = listOfNotNull(airedBegin, airedEnd).joinToString(" to ")
            append("**Aired:** $aired\n")
        }
    }.trim()

    private fun mappedStatus(): Int = when (status) {
        "finished_airing" -> SAnime.COMPLETED
        "currently_airing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun fancyScore(score: Int): String {
        if (score <= 0) return ""
        val stars = (score / 20.0).roundToInt().coerceIn(1, 5)
        return "${"★".repeat(stars)}${"☆".repeat(5 - stars)} $score"
    }
}

@Serializable
class AlternativeTitle(
    val type: String? = null,
    val title: String? = null,
)

@Serializable
class RelatedResponse(
    @SerialName("get_q02") val node: RelatedNode? = null,
)

@Serializable
class RelatedNode(
    val data: RelatedNodeData? = null,
    val relations: List<RelationItem> = emptyList(),
)

@Serializable
class RelatedNodeData(
    @SerialName("ani_id") val aniId: String? = null,
)

@Serializable
class RelationItem(
    @SerialName("ani_id") val aniId: String? = null,
    val title: String? = null,
    private val urlCover600: String? = null,
) {
    fun toSAnime(resolvedId: String, baseUrl: String): SAnime = SAnime.create().apply {
        url = resolvedId
        this@RelationItem.title?.let { title = it }
        thumbnail_url = urlCover600?.let { if (it.startsWith("http")) it else baseUrl + it }
    }
}

@Serializable
class EpisodesResponse(
    @SerialName("get_q01") val episodesData: EpisodesData? = null,
)

@Serializable
class NodeResponse(
    @SerialName("get_q02") val node: SearchItem? = null,
)

@Serializable
class EpisodesData(
    val paging: Paging? = null,
    val items: List<EpisodeItem> = emptyList(),
)

@Serializable
class EpisodeItem(
    private val data: EpisodeData,
) {
    val epId: String get() = data.epId

    val epSlug: String? get() = data.epSlug

    fun toSEpisode(): SEpisode = data.toSEpisode()
}

@Serializable
class EpisodeData(
    @SerialName("ani_id") private val aniId: String,
    @SerialName("ep_id") val epId: String,
    @SerialName("ep_index") private val index: Int = 0,
    @SerialName("ep_sub_index") private val subIndex: Int? = null,
    @SerialName("ep_title") private val title: String? = null,
    private val epPath: String? = null,
    @SerialName("date_create") private val createDate: Long? = null,
    @SerialName("date_update") private val updateDate: Long? = null,
    @SerialName("sourcesNode_list") val sourcesList: List<SourceNode>? = null,
) {
    val epSlug: String?
        get() = epPath?.let { path ->
            val last = path.substringAfterLast("/")
            val dashIndex = last.indexOf('-')
            if (dashIndex != -1) last.substring(dashIndex + 1) else null
        }

    fun toSEpisode() = SEpisode.create().apply {
        val epSubIndexValue = subIndex ?: 0

        url = "$aniId/$epId"

        name = if (epSubIndexValue > 0) {
            "Ep $index.$epSubIndexValue: $title"
        } else {
            "Ep $index: $title"
        }

        episode_number = if (epSubIndexValue > 0) {
            index.toFloat() + (epSubIndexValue.toFloat() / 10f)
        } else {
            index.toFloat()
        }

        date_upload = updateDate ?: createDate ?: 0L

        scanlator = sourcesList
            ?.mapNotNull { it.data?.srcType?.lowercase() }
            ?.distinct()
            ?.sortedBy {
                when (it) {
                    "sub" -> 1
                    "raw" -> 2
                    "dub" -> 3
                    else -> 4
                }
            }
            ?.joinToString(" & ") { it.replaceFirstChar(Char::titlecase) }
    }
}

@Serializable
class VideoUrlResponse(
    @SerialName("get_q07") val videoUrlData: VideoUrlData? = null,
)

@Serializable
class VideoUrlData(
    val data: EpisodeSourcesData? = null,
)

@Serializable
class EpisodeSourcesData(
    @SerialName("sourcesNode_list") val sourcesList: List<SourceNode> = emptyList(),
)

@Serializable
class SourceNode(
    val data: SourceData? = null,
)

@Serializable
class SourceData(
    @SerialName("src_name") val srcName: String? = null,
    @SerialName("src_type") val srcType: String? = null,
    val souPath: String? = null,
    @SerialName("m3u8_lists") val m3u8Lists: List<M3u8List> = emptyList(),
    @SerialName("track") val tracks: List<TrackData> = emptyList(),
)

@Serializable
class TrackData(
    val label: String? = null,
    val kind: String? = null,
    val default: JsonElement? = null,
    val local: JsonElement? = null,
    val trackPath: String? = null,
)

@Serializable
class M3u8List(
    val name: String? = null,
    val iframe: String? = null,
)

val GENRE_MAP = mapOf(
    "Comedy" to "comedy", "Action" to "action", "Fantasy" to "fantasy", "Drama" to "drama",
    "Adventure" to "adventure", "Sci Fi" to "sci_fi", "Romance" to "romance", "Slice of Life" to "slice_of_life",
    "Shounen" to "shounen", "School" to "school", "Supernatural" to "supernatural", "Seinen" to "seinen",
    "Ecchi" to "ecchi", "Mecha" to "mecha", "Mystery" to "mystery", "Historical" to "historical",
    "Magic" to "magic", "Martial Arts" to "martial_arts", "Shoujo" to "shoujo", "Sports" to "sports",
    "Super Power" to "super_power", "Psychological" to "psychological", "Military" to "military", "Adult Cast" to "adult_cast",
    "Music" to "music", "Harem" to "harem", "Horror" to "horror", "Isekai" to "isekai",
    "Space" to "space", "Demons" to "demons", "Mythology" to "mythology", "Game" to "game",
    "Mahou Shoujo" to "mahou_shoujo", "Kids" to "kids", "Parody" to "parody", "Suspense" to "suspense",
    "Thriller" to "thriller", "Cgdct" to "cgdct", "Gore" to "gore", "Police" to "police",
    "Samurai" to "samurai", "Anthropomorphic" to "anthropomorphic", "Workplace" to "workplace", "Gag Humor" to "gag_humor",
    "Detective" to "detective", "Iyashikei" to "iyashikei", "Strategy Game" to "strategy_game", "Vampire" to "vampire",
    "Award Winning" to "award_winning", "Idols Female" to "idols_female", "Josei" to "josei", "Team Sports" to "team_sports",
    "Shoujo Ai" to "shoujo_ai", "Otaku Culture" to "otaku_culture", "Time Travel" to "time_travel", "Reincarnation" to "reincarnation",
    "Avant Garde" to "avant_garde", "Shounen Ai" to "shounen_ai", "Love Polygon" to "love_polygon", "Gourmet" to "gourmet",
    "Organized Crime" to "organized_crime", "Boys Love" to "boys_love", "Girls Love" to "girls_love", "Performing Arts" to "performing_arts",
    "Video Game" to "video_game", "Dementia" to "dementia", "Racing" to "racing", "Combat Sports" to "combat_sports",
    "Visual Arts" to "visual_arts", "Idols Male" to "idols_male", "High Stakes Game" to "high_stakes_game", "Survival" to "survival",
    "Delinquents" to "delinquents", "Reverse Harem" to "reverse_harem", "Childcare" to "childcare", "Cars" to "cars",
    "Crossdressing" to "crossdressing", "Pets" to "pets", "Erotica" to "erotica", "Romantic Subtext" to "romantic_subtext",
    "Showbiz" to "showbiz", "Urban Fantasy" to "urban_fantasy", "Magical Sex Shift" to "magical_sex_shift", "Medical" to "medical",
    "Educational" to "educational", "Love Status Quo" to "love_status_quo", "Villainess" to "villainess",
)

private val REVERSE_GENRE_MAP = GENRE_MAP.entries.associate { it.value to it.key }

private fun String.normalizeGenre() = REVERSE_GENRE_MAP[this] ?: this.split("_")
    .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

@Serializable
class SearchVariables(
    private val select: SearchSelect,
)

@Serializable
class SearchSelect(
    private val word: String,
    private val sortby: String,
    private val page: Int,
    private val incGenres: List<String> = emptyList(),
    private val excGenres: List<String> = emptyList(),
    private val origStatus: String = "",
    private val type: String = "",
    @SerialName("year_from") private val yearFrom: String? = null,
    @SerialName("year_to") private val yearTo: String? = null,
    private val season: String? = null,
    private val sources: String = "",
    @SerialName("ep_total") private val epTotal: String = "",
    private val ignoreGlobalGenres: Boolean = false,
    private val ignoreGlobalBlocks: Boolean = false,
    private val size: Int = 24,
)

@Serializable
class EpisodeVariables(
    private val select: EpisodeSelect,
)

@Serializable
class DetailsVariables(
    val getAnimesNodeId: String,
)

@Serializable
class EpisodeSelect(
    @SerialName("ani_id") private val aniId: String,
    private val init: Int = 50,
    private val size: Int = 50,
    private val page: Int = 1,
)

@Serializable
class VideoVariables(
    private val select: VideoSelect,
)

@Serializable
class VideoSelect(
    val id: String,
)

@Serializable
class GraphQlPayload(
    private val query: String,
    private val variables: JsonElement,
)
