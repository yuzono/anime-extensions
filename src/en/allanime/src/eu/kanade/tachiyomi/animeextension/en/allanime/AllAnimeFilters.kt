package eu.kanade.tachiyomi.animeextension.en.allanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object AllAnimeFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String = (this.getFirst<R>() as QueryPartFilter).toQueryPart()

    private inline fun <reified R> AnimeFilterList.getFirst(): R = this.filterIsInstance<R>().first()

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): String = (this.getFirst<R>() as CheckBoxFilterList).state
        .mapNotNull { checkbox ->
            if (checkbox.state) {
                options.find { it.first == checkbox.name }!!.second
            } else {
                null
            }
        }.joinToString("\",\"").let {
            if (it.isBlank()) {
                "all"
            } else {
                "[\"$it\"]"
            }
        }

    class OriginFilter : QueryPartFilter("Origin", AllAnimeFiltersData.ORIGIN)
    class SeasonFilter : QueryPartFilter("Season", AllAnimeFiltersData.SEASONS)
    class ReleaseYearFilter : QueryPartFilter("Released at", AllAnimeFiltersData.YEARS)
    class SortByFilter : QueryPartFilter("Sort By", AllAnimeFiltersData.SORT_BY)

    class TypesFilter :
        CheckBoxFilterList(
            "Types",
            AllAnimeFiltersData.TYPES.map { CheckBoxVal(it.first, false) },
        )

    class GenresFilter :
        CheckBoxFilterList(
            "Genres",
            AllAnimeFiltersData.GENRES.map { CheckBoxVal(it.first, false) },
        )

    val FILTER_LIST get() = AnimeFilterList(
        OriginFilter(),
        SeasonFilter(),
        ReleaseYearFilter(),
        SortByFilter(),
        AnimeFilter.Separator(),
        TypesFilter(),
        GenresFilter(),
    )

    data class FilterSearchParams(
        val origin: String = "",
        val season: String = "",
        val releaseYear: String = "",
        val sortBy: String = "",
        val types: String = "",
        val genres: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<OriginFilter>(),
            filters.asQueryPart<SeasonFilter>(),
            filters.asQueryPart<ReleaseYearFilter>(),
            filters.asQueryPart<SortByFilter>(),
            filters.parseCheckbox<TypesFilter>(AllAnimeFiltersData.TYPES),
            filters.parseCheckbox<GenresFilter>(AllAnimeFiltersData.GENRES),
        )
    }

    private object AllAnimeFiltersData {
        val ALL = Pair("All", "all")

        val ORIGIN = arrayOf(
            Pair("All", "ALL"),
            Pair("Japan", "JP"),
            Pair("China", "CN"),
            Pair("Korea", "KR"),
        )

        val SEASONS = arrayOf(
            ALL,
            Pair("Winter", "Winter"),
            Pair("Spring", "Spring"),
            Pair("Summer", "Summer"),
            Pair("Fall", "Fall"),
        )

        // current year, but not less than 2026
        private val currentYear = Calendar.getInstance().get(Calendar.YEAR).coerceAtLeast(2026)
        val YEARS = arrayOf(ALL) + (currentYear + 1 downTo 1975)
            .map { Pair(it.toString(), it.toString()) }
            .toTypedArray()

        val SORT_BY = arrayOf(
            Pair("Update", "Recent"),
            Pair("Name Asc", "Name_ASC"),
            Pair("Name Desc", "Name_DESC"),
            Pair("Ratings", "Top"),
        )

        val TYPES = arrayOf(
            Pair("Movie", "Movie"),
            Pair("ONA", "ONA"),
            Pair("OVA", "OVA"),
            Pair("Special", "Special"),
            Pair("TV", "TV"),
            Pair("Unknown", "Unknown"),
        )

        val GENRES = arrayOf(
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Cars", "Cars"),
            Pair("Comedy", "Comedy"),
            Pair("Dementia", "Dementia"),
            Pair("Demons", "Demons"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Game", "Game"),
            Pair("Harem", "Harem"),
            Pair("Historical", "Historical"),
            Pair("Horror", "Horror"),
            Pair("Isekai", "Isekai"),
            Pair("Josei", "Josei"),
            Pair("Kids", "Kids"),
            Pair("Magic", "Magic"),
            Pair("Martial Arts", "Martial Arts"),
            Pair("Mecha", "Mecha"),
            Pair("Military", "Military"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Parody", "Parody"),
            Pair("Police", "Police"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("Samurai", "Samurai"),
            Pair("School", "School"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Seinen", "Seinen"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shoujo Ai", "Shoujo Ai"),
            Pair("Shounen", "Shounen"),
            Pair("Shounen Ai", "Shounen Ai"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Space", "Space"),
            Pair("Sports", "Sports"),
            Pair("Super Power", "Super Power"),
            Pair("Supernatural", "Supernatural"),
            Pair("Thriller", "Thriller"),
            Pair("Unknown", "Unknown"),
            Pair("Vampire", "Vampire"),
            Pair("Yaoi", "Yaoi"),
            Pair("Yuri", "Yuri"),
        )
    }
}
