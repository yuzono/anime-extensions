package eu.kanade.tachiyomi.animeextension.all.animetsu

import eu.kanade.tachiyomi.animeextension.all.animetsu.Animetsu.Companion.newLineRegex
import eu.kanade.tachiyomi.animeextension.all.animetsu.Animetsu.Companion.parseStatus
import eu.kanade.tachiyomi.animeextension.all.animetsu.Animetsu.Companion.textStyleRegex
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
data class AnimetsuSearchDto(
    val results: List<AnimetsuAnimeDto>,
    val page: Int,
    @SerialName("last_page") val lastPage: Int,
    val total: Int,
)

@Serializable
data class AnimetsuRecentDto(
    val results: List<AnimetsuAnimeDto>,
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
)

@Serializable
data class AnimetsuAnimeDto(
    val id: String,
    val type: String? = null,
    val title: AnimetsuTitleDto? = null,
    val status: String? = null,
    @SerialName("is_adult") val isAdult: Boolean = false,
    @SerialName("cover_image") val coverImage: AnimetsuCoverDto? = null,
    val banner: String? = null,
    val description: String? = null,
    @SerialName("total_eps") val totalEps: Int? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val year: Int? = null,
    val format: String? = null,
    val duration: Int? = null,
    val genres: List<String>? = null,
    val tags: List<String>? = null,
    @SerialName("average_score") val averageScore: Int? = null,
    val trailer: String? = null,
    val season: String? = null,
    val seasons: List<AnimetsuSeasonDto>? = null,
    val episodes: List<AnimetsuEpisodeDto>? = null,
    @SerialName("anilist_id") val anilistId: Int? = null,
    @SerialName("mal_id") val malId: Int? = null,
    val country: String? = null,
    val source: String? = null,
    val hashtag: String? = null,
    @SerialName("mean_score") val meanScore: Int? = null,
    val popularity: Int? = null,
    val favourites: Int? = null,
    val trending: Int? = null,
    val synonyms: List<String>? = null,
    val studios: List<AnimetsuStudioDto>? = null,
    val relations: List<AnimetsuRelationDto>? = null,
    val characters: List<AnimetsuCharacterDto>? = null,
    val recommendations: List<AnimetsuRecommendationDto>? = null,
    val staff: List<AnimetsuStaffDto>? = null,
    val color: String? = null,
    @SerialName("clear_logo") val clearLogo: String? = null,
    val users: Int? = null,
) {
    fun toSAnime(titleLanguage: String): SAnime? = SAnime.create().apply {
        val dto = this@AnimetsuAnimeDto
        url = dto.id
        title = dto.title?.preferredTitle(titleLanguage) ?: return null
        thumbnail_url = dto.coverImage?.large ?: dto.coverImage?.medium
        genre = (dto.genres.orEmpty() + dto.tags.orEmpty()).joinToString()
        status = parseStatus(dto.status)
        description = dto.buildDescription()
        artist = dto.staff?.filter {
            it.role in listOf("Original Story", "Original Creator", "Original Character Design")
        }?.mapNotNull { it.name }?.joinToString()
        author = dto.studios?.firstOrNull { it.isMain }?.name
            ?: dto.studios?.joinToString { it.name }
    }

    fun buildDescription(): String {
        val desc = StringBuilder()

        description?.cleanHtml()?.let {
            desc.append(it)
        }

        val meta = mutableListOf<String>()
        format?.let { meta.add(it.replace("_", " ").titleCase()) }
        source?.let { meta.add("**Source**: ${it.replace("_", " ").titleCase()}") }
        status?.let {
            val statusStr = when (it) {
                "RELEASING" -> "Airing"
                "FINISHED" -> "Finished"
                "NOT_YET_RELEASED" -> "Upcoming"
                "CANCELLED" -> "Cancelled"
                else -> it.replace("_", " ").titleCase()
            }
            meta.add(statusStr)
        }
        totalEps?.let { meta.add("**Episodes**: $it") }
        duration?.let { meta.add("**Duration**: $it min") }
        season?.let { season ->
            val year = year
            meta.add(if (year != null) "${season.titleCase()} $year" else season.titleCase())
        }
        country?.let { meta.add("**Country**: $it") }
        if (meta.isNotEmpty()) {
            if (desc.isNotBlank()) desc.append("\n\n")
            desc.append(meta.joinToString(" | "))
        }

        val dates = mutableListOf<String>()
        startDate?.let { dates.add("**Start**: $it") }
        endDate?.let { dates.add("**End**: $it") }
        if (dates.isNotEmpty()) {
            if (desc.isNotBlank()) desc.append("\n\n")
            desc.append(dates.joinToString(" | "))
        }

        averageScore?.let { score ->
            if (desc.isNotBlank()) desc.append("\n\n")
            desc.append("**Score**: $score/100")
            meanScore?.takeIf { it != score }?.let { mean ->
                desc.append(" *(Mean: $mean/100)*")
            }
        }

        val stats = mutableListOf<String>()
        popularity?.let { stats.add("**Popularity**: $it") }
        favourites?.let { stats.add("**Favourites**: $it") }
        trending?.let { if (it > 0) stats.add("**Trending**: $it") }
        if (stats.isNotEmpty()) {
            if (desc.isNotBlank()) desc.append("\n")
            desc.append(stats.joinToString(" | "))
        }

        studios?.takeIf { it.isNotEmpty() }?.let { studios ->
            val mainStudio = studios.firstOrNull { it.isMain }?.name
            val otherStudios = studios.filter { !it.isMain }.map { it.name }
            if (desc.isNotBlank()) desc.append("\n\n")
            desc.append("**Studio**: ")
            if (mainStudio != null && otherStudios.isNotEmpty()) {
                desc.append("$mainStudio (${otherStudios.joinToString(", ")})")
            } else {
                desc.append(studios.joinToString(", ") { it.name })
            }
        }

        synonyms?.takeIf { it.isNotEmpty() }?.let {
            if (desc.isNotBlank()) desc.append("\n\n")
            desc.append("**Synonyms**: ").append(it.joinToString(", "))
        }

        hashtag?.takeIf { it.isNotBlank() }?.let {
            if (desc.isNotBlank()) desc.append("\n")
            desc.append("**Hashtag**: $it")
        }

        relations?.takeIf { it.isNotEmpty() }?.let { relations ->
            if (desc.isNotBlank()) desc.append("\n\n")
            desc.append("**Relations**:")
            relations.forEach { rel ->
                val relTitle = rel.title?.english ?: rel.title?.romaji ?: rel.title?.native ?: "Unknown"
                val relType = rel.relationType?.replace("_", " ")?.titleCase() ?: ""
                val relFormat = rel.format?.replace("_", " ")?.titleCase() ?: ""
                val relSeasonYear = buildString {
                    rel.season?.let { append(it.titleCase()) }
                    rel.year?.let { y ->
                        if (isNotEmpty()) append(" ")
                        append(y)
                    }
                }
                desc.append("\n* $relTitle ($relFormat${if (relSeasonYear.isNotBlank()) ", $relSeasonYear" else ""}) [$relType]")
            }
        }

        characters?.filter { it.role == "MAIN" }?.takeIf { it.isNotEmpty() }?.let { chars ->
            if (desc.isNotBlank()) desc.append("\n\n")
            desc.append("**Main Characters**:")
            chars.forEach { char ->
                val va = char.voiceActor?.let { "${it.name} (${it.language})" } ?: "Unknown"
                desc.append("\n* ${char.name} (VA: $va)")
            }
        }

        staff?.takeIf { it.isNotEmpty() }?.let { staffList ->
            if (desc.isNotBlank()) desc.append("\n\n")
            desc.append("**Staff**:")
            staffList.forEach { s ->
                desc.append("\n* ${s.role}: ${s.name}")
            }
        }

        val ids = mutableListOf<String>()
        anilistId?.let { ids.add("[AniList](https://anilist.co/anime/$it)") }
        malId?.let { ids.add("[MAL](https://myanimelist.net/anime/$it)") }
        if (ids.isNotEmpty()) {
            if (desc.isNotBlank()) desc.append("\n\n")
            desc.append(ids.joinToString(" | "))
        }

        trailer?.takeIf { it.isNotBlank() && it != "-" }?.let {
            if (desc.isNotBlank()) desc.append("\n\n")
            desc.append("[Trailer](https://www.youtube.com/watch?v=$it)")
        }

        return desc.toString().trim()
    }

    private fun String.titleCase(): String = split(" ").joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun String.cleanHtml(): String = this
        .replace(newLineRegex, "\n")
        .replace(textStyleRegex, "")
        .trim()
}

@Serializable
data class AnimetsuTitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
) {
    fun preferredTitle(language: String): String? = when (language) {
        "english" -> english
        "native" -> native
        else -> romaji
    }?.takeIf(String::isNotBlank)
        ?: listOfNotNull(
            romaji,
            english,
            native,
        ).firstOrNull(String::isNotBlank)
}

@Serializable
data class AnimetsuCoverDto(
    val large: String? = null,
    val medium: String? = null,
    val small: String? = null,
)

@Serializable
data class AnimetsuSeasonDto(
    val id: String,
    val title: AnimetsuTitleDto? = null,
    val status: String? = null,
    val relation: String? = null,
)

@Serializable
data class AnimetsuEpisodeDto(
    @SerialName("ep_num") val epNum: Double? = null, // Was Int?
    @SerialName("aired_at") val airedAt: String? = null,
    val desc: String? = null,
    @SerialName("is_filler") val isFiller: Boolean? = null,
    val name: String? = null,
    val id: String = "",
) {
    companion object {
        private val DATE_FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun toSEpisode(animeId: String): SEpisode? {
        val epNum = this.epNum ?: return null
        if (epNum <= 0.0) return null // That Time I Got Reincarnated as a Slime Season 3 has an Episode 0 that does not exist
        val dtoName = this.name
        val dtoFiller = this.isFiller
        val dtoAiredAt = this.airedAt

        // Format: 17.5 → "17.5", 1.0 → "1"; e.g. That Time I Got Reincarnated as a Slime Season 3
        val epNumStr = if (epNum % 1.0 == 0.0) epNum.toInt().toString() else epNum.toString()

        return SEpisode.create().apply {
            url = "$animeId/$epNum"
            name = buildString {
                append("Ep. $epNumStr")
                if (!dtoName.isNullOrBlank()) append(" - $dtoName")
                if (dtoFiller == true) append(" (Filler)")
            }
            episode_number = epNum.toFloat()
            date_upload = DATE_FORMATTER.tryParse(dtoAiredAt) // Now uses the shared constant
        }
    }
}

@Serializable
data class AnimetsuServerDto(
    val id: String,
    val default: Boolean = false,
    val tip: String? = null,
)

@Serializable
data class AnimetsuVideoDto(
    val sources: List<AnimetsuSourceDto>,
    val subs: List<AnimetsuSubDto>? = null,
    val skips: AnimetsuSkipsDto? = null,
    val from: String? = null,
    val server: String? = null,
)

@Serializable
data class AnimetsuSourceDto(
    val quality: String,
    val url: String,
    @SerialName("old_hls") val oldHls: Boolean = false,
    val type: String? = null,
    @SerialName("need_proxy") val needProxy: Boolean = false,
)

@Serializable
data class AnimetsuSubDto(
    val url: String,
    val lang: String? = null,
)

@Serializable
data class AnimetsuSkipsDto(
    val intro: AnimetsuSkipTimeDto? = null,
    val outro: AnimetsuSkipTimeDto? = null,
    @SerialName("ep_num") val epNum: Int? = null,
)

@Serializable
data class AnimetsuSkipTimeDto(
    val start: Double,
    val end: Double,
)

@Serializable
data class AnimetsuStudioDto(
    val name: String,
    @SerialName("anilist_id") val anilistId: Int? = null,
    @SerialName("is_main") val isMain: Boolean = false,
)

@Serializable
data class AnimetsuRelationDto(
    val id: String,
    @SerialName("anilist_id") val anilistId: Int? = null,
    val title: AnimetsuTitleDto? = null,
    val format: String? = null,
    val season: String? = null,
    val year: Int? = null,
    @SerialName("relation_type") val relationType: String? = null,
    @SerialName("total_eps") val totalEps: Int? = null,
    val status: String? = null,
)

@Serializable
data class AnimetsuCharacterDto(
    @SerialName("anilist_id") val anilistId: Int? = null,
    val name: String? = null,
    val image: String? = null,
    val role: String? = null,
    @SerialName("voice_actor") val voiceActor: AnimetsuVoiceActorDto? = null,
)

@Serializable
data class AnimetsuVoiceActorDto(
    @SerialName("anilist_id") val anilistId: Int? = null,
    val name: String? = null,
    val image: String? = null,
    val language: String? = null,
)

@Serializable
data class AnimetsuRecommendationDto(
    val id: String,
    @SerialName("anilist_id") val anilistId: Int? = null,
    val title: AnimetsuTitleDto? = null,
    val format: String? = null,
    val season: String? = null,
    val year: Int? = null,
    @SerialName("total_eps") val totalEps: Int? = null,
    val status: String? = null,
    @SerialName("average_score") val averageScore: Int? = null,
    val description: String? = null,
    @SerialName("cover_image") val coverImage: AnimetsuCoverDto? = null,
    val banner: String? = null,
    val trailer: String? = null,
)

@Serializable
data class AnimetsuStaffDto(
    @SerialName("anilist_id") val anilistId: Int? = null,
    val name: String? = null,
    val image: String? = null,
    val language: String? = null,
    val role: String? = null,
)
