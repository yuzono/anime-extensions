package eu.kanade.tachiyomi.animeextension.es.lamovie

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.firstInstance

internal object LaMovieFilters {
    private val TYPE_OPTIONS = arrayOf(
        "Todas" to "",
        "Películas" to "movie",
        "Series" to "tvshow",
        "Anime" to "anime",
    )

    private val SORT_OPTIONS = arrayOf(
        "Más populares" to "popular",
        "Más recientes" to "recent",
        "Mejor valorados" to "rating",
    )

    private val GENRE_OPTIONS = arrayOf(
        "Todos" to "",
        "Acción" to "acción",
        "Action & Adventure" to "action-adventure",
        "Animación" to "animación",
        "Aventura" to "aventura",
        "Bélica" to "bélica",
        "Ciencia ficción" to "ciencia-ficción",
        "Comedia" to "comedia",
        "Crimen" to "crimen",
        "Documental" to "documental",
        "Drama" to "drama",
        "Familia" to "familia",
        "Fantasía" to "fantasía",
        "Historia" to "historia",
        "Kids" to "kids",
        "Misterio" to "misterio",
        "Música" to "música",
        "Película de TV" to "película-de-tv",
        "Reality" to "reality",
        "Romance" to "romance",
        "Sci-Fi & Fantasy" to "sci-fi-fantasy",
        "Soap" to "soap",
        "Suspense" to "suspense",
        "Terror" to "terror",
        "War & Politics" to "war-politics",
        "Western" to "western",
    )

    open class QueryPartFilter(
        displayName: String,
        private val entries: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        entries.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart(): String = entries[state].second
    }

    class TypeFilter : QueryPartFilter("Tipo", TYPE_OPTIONS)
    class SortFilter : QueryPartFilter("Ordenar por", SORT_OPTIONS)
    class GenreFilter : QueryPartFilter("Género", GENRE_OPTIONS)
    class YearFilter : AnimeFilter.Text("Año")

    class FilterSearchParams(
        val type: String = "",
        val sort: String = "popular",
        val genre: String = "",
        val year: String = "",
    )

    fun createFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        SortFilter(),
        GenreFilter(),
        YearFilter(),
    )

    fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            type = filters.firstInstance<TypeFilter>().toQueryPart(),
            sort = filters.firstInstance<SortFilter>().toQueryPart(),
            genre = filters.firstInstance<GenreFilter>().toQueryPart(),
            year = filters.firstInstance<YearFilter>().state.trim(),
        )
    }
}
