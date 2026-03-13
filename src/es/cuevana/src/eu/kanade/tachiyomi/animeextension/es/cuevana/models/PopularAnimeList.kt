package eu.kanade.tachiyomi.animeextension.es.cuevana.models
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PopularAnimeList(
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
data class Titles(
    @SerialName("name") val name: String? = null,
    @SerialName("original") val original: Original? = Original(),
)

@Serializable
data class Images(
    @SerialName("poster") val poster: String? = null,
    @SerialName("backdrop") val backdrop: String? = null,
)

@Serializable
data class Rate(
    @SerialName("average") val average: Double? = null,
    @SerialName("votes") val votes: Int? = null,
)

@Serializable
data class Genres(
    @SerialName("id") val id: String? = null,
    @SerialName("slug") val slug: String? = null,
    @SerialName("name") val name: String? = null,
)

@Serializable
data class Acting(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
)

@Serializable
data class Cast(
    @SerialName("acting") val acting: ArrayList<Acting> = arrayListOf(),
    @SerialName("directing") val directing: ArrayList<Directing> = arrayListOf(),
)

@Serializable
data class Url(
    @SerialName("slug") val slug: String? = null,
)

@Serializable
data class Slug(
    @SerialName("name") val name: String? = null,
    @SerialName("season") val season: String? = null,
    @SerialName("episode") val episode: String? = null,
)

@Serializable
data class Movies(
    @SerialName("titles") val titles: Titles? = Titles(),
    @SerialName("images") val images: Images? = Images(),
    @SerialName("rate") val rate: Rate? = Rate(),
    @SerialName("overview") val overview: String? = null,
    @SerialName("TMDbId") val TMDbId: String? = null,
    @SerialName("genres") val genres: ArrayList<Genres> = arrayListOf(),
    @SerialName("cast") val cast: Cast? = Cast(),
    @SerialName("runtime") val runtime: Int? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
    @SerialName("url") val url: Url? = Url(),
    @SerialName("slug") val slug: Slug? = Slug(),
)

@Serializable
data class PageProps(
    @SerialName("thisSerie") val thisSerie: ThisSerie? = ThisSerie(),
    @SerialName("thisMovie") val thisMovie: ThisMovie? = ThisMovie(),
    @SerialName("movies") val movies: ArrayList<Movies> = arrayListOf(),
    @SerialName("pages") val pages: Int? = null,
    @SerialName("season") val season: Season? = Season(),
    @SerialName("episode") val episode: Episode? = Episode(),
)

@Serializable
data class Props(
    @SerialName("pageProps") val pageProps: PageProps? = PageProps(),
    @SerialName("__N_SSG") val _NSSG: Boolean? = null,
)

@Serializable
data class Query(
    @SerialName("page") val page: String? = null,
    @SerialName("serie") val serie: String? = null,
    @SerialName("movie") val movie: String? = null,
    @SerialName("episode") val episode: String? = null,
    @SerialName("q") val q: String? = null,
)

@Serializable
data class Directing(
    @SerialName("name") val name: String? = null,
)

@Serializable
data class Server(
    @SerialName("cyberlocker") val cyberlocker: String? = null,
    @SerialName("result") val result: String? = null,
    @SerialName("quality") val quality: String? = null,
)

@Serializable
data class Videos(
    @SerialName("latino") val latino: ArrayList<Server> = arrayListOf(),
    @SerialName("spanish") val spanish: ArrayList<Server> = arrayListOf(),
    @SerialName("english") val english: ArrayList<Server> = arrayListOf(),
    @SerialName("japanese") val japanese: ArrayList<Server> = arrayListOf(),
)

@Serializable
data class Downloads(
    @SerialName("cyberlocker") val cyberlocker: String? = null,
    @SerialName("result") val result: String? = null,
    @SerialName("quality") val quality: String? = null,
    @SerialName("language") val language: String? = null,
)

@Serializable
data class ThisMovie(
    @SerialName("TMDbId") val TMDbId: String? = null,
    @SerialName("titles") val titles: Titles? = Titles(),
    @SerialName("images") val images: Images? = Images(),
    @SerialName("overview") val overview: String? = null,
    @SerialName("runtime") val runtime: Int? = null,
    @SerialName("genres") val genres: ArrayList<Genres> = arrayListOf(),
    @SerialName("cast") val cast: Cast? = Cast(),
    @SerialName("rate") val rate: Rate? = Rate(),
    @SerialName("url") val url: Url? = Url(),
    @SerialName("slug") val slug: Slug? = Slug(),
    @SerialName("releaseDate") val releaseDate: String? = null,
    @SerialName("videos") val videos: Videos? = Videos(),
    @SerialName("downloads") val downloads: ArrayList<Downloads> = arrayListOf(),
)

@Serializable
data class Season(
    @SerialName("number") val number: Int? = null,
)

@Serializable
data class NextEpisode(
    @SerialName("title") val title: String? = null,
    @SerialName("slug") val slug: String? = null,
)

@Serializable
data class Episode(
    @SerialName("TMDbId") val TMDbId: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("number") val number: Int? = null,
    @SerialName("image") val image: String? = null,
    @SerialName("url") val url: Url? = Url(),
    @SerialName("slug") val slug: Slug? = Slug(),
    @SerialName("nextEpisode") val nextEpisode: NextEpisode? = NextEpisode(),
    @SerialName("previousEpisode") val previousEpisode: String? = null,
    @SerialName("videos") val videos: Videos? = Videos(),
    @SerialName("downloads") val downloads: ArrayList<Downloads> = arrayListOf(),
)
