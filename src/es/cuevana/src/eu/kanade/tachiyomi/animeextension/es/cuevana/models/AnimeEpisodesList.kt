package eu.kanade.tachiyomi.animeextension.es.cuevana.models

import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
data class AnimeEpisodesList(
    @SerialName("props") val props: Props? = Props(),
    @SerialName("page") val page: String? = null,
    @SerialName("query") val query: Query? = Query(),
    @SerialName("buildId") val buildId: String? = null,
    @SerialName("isFallback") val isFallback: Boolean? = null,
    @SerialName("gsp") val gsp: Boolean? = null,
    @SerialName("locale") val locale: String? = null,
    @SerialName("locales") val locales: ArrayList<String> = arrayListOf(),
    @SerialName("defaultLocale") val defaultLocale: String? = null,
    @SerialName("scriptLoader") val scriptLoader: ArrayList<String> = arrayListOf(),
)

@Serializable
data class Episodes(
    @SerialName("title") val title: String? = null,
    @SerialName("TMDbId") val TMDbId: String? = null,
    @SerialName("number") val number: Int? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
    @SerialName("image") val image: String? = null,
    @SerialName("url") val url: Url? = Url(),
    @SerialName("slug") val slug: Slug? = Slug(),
) {
    fun toSEpisode(): SEpisode {
        return SEpisode.create().apply {
            date_upload = releaseDate?.toDate() ?: 0L
            name = "T${slug?.season} - Episodio ${slug?.episode}"
            episode_number = number?.toFloat()!!
            url = "/episodio/${slug?.name}-temporada-${slug?.season}-episodio-${slug?.episode}"
        }
    }
}

// 2026-01-21T00:00:00.000Z
private val dateFormatter by lazy {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
}

private fun String.toDate(): Long {
    return runCatching { dateFormatter.parse(trim())?.time }
        .getOrNull() ?: 0L
}

@Serializable
data class Seasons(
    @SerialName("number") val number: Int? = null,
    @SerialName("episodes") val episodes: ArrayList<Episodes> = arrayListOf(),
)

@Serializable
data class Original(
    @SerialName("name") val name: String? = null,
)

@Serializable
data class ThisSerie(
    @SerialName("TMDbId") val TMDbId: String? = null,
    @SerialName("seasons") val seasons: ArrayList<Seasons> = arrayListOf(),
    @SerialName("titles") val titles: Titles? = Titles(),
    @SerialName("images") val images: Images? = Images(),
    @SerialName("overview") val overview: String? = null,
    @SerialName("genres") val genres: ArrayList<Genres> = arrayListOf(),
    @SerialName("cast") val cast: Cast? = Cast(),
    @SerialName("rate") val rate: Rate? = Rate(),
    @SerialName("url") val url: Url? = Url(),
    @SerialName("slug") val slug: Slug? = Slug(),
    @SerialName("releaseDate") val releaseDate: String? = null,
)
