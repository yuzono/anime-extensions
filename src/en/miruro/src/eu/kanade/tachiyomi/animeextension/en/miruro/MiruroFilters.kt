package eu.kanade.tachiyomi.animeextension.en.miruro

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object MiruroFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) :
        AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (this.getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> {
        return (this.getFirst<R>() as CheckBoxFilterList).state
            .mapNotNull { checkbox ->
                if (checkbox.state) {
                    options.find { it.first == checkbox.name }!!.second
                } else {
                    null
                }
            }
    }

    class SortFilter : QueryPartFilter("Sort", MiruroFiltersData.SORT)

    class GenreFilter : CheckBoxFilterList(
        "Genres",
        MiruroFiltersData.GENRES.map { CheckBoxVal(it.first, false) },
    )

    class YearFilter : QueryPartFilter("Year", MiruroFiltersData.YEARS)

    class SeasonFilter : QueryPartFilter("Season", MiruroFiltersData.SEASONS)

    class StatusFilter : QueryPartFilter("Status", MiruroFiltersData.STATUS)

    class FormatFilter : CheckBoxFilterList(
        "Format",
        MiruroFiltersData.FORMATS.map { CheckBoxVal(it.first, false) },
    )

    val FILTER_LIST get() = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
        FormatFilter(),
        AnimeFilter.Separator(),
        YearFilter(),
        SeasonFilter(),
        StatusFilter(),
    )

    data class FilterSearchParams(
        val sort: String = "",
        val genres: List<String> = emptyList(),
        val year: String = "",
        val season: String = "",
        val status: String = "",
        val formats: List<String> = emptyList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            sort = filters.asQueryPart<SortFilter>(),
            genres = filters.parseCheckbox<GenreFilter>(MiruroFiltersData.GENRES),
            year = filters.asQueryPart<YearFilter>(),
            season = filters.asQueryPart<SeasonFilter>(),
            status = filters.asQueryPart<StatusFilter>(),
            formats = filters.parseCheckbox<FormatFilter>(MiruroFiltersData.FORMATS),
        )
    }

    private object MiruroFiltersData {

        val ALL = Pair("All", "all")

        val SORT = arrayOf(
            ALL,
            Pair("Popularity", "POPULARITY_DESC"),
            Pair("Average Score", "SCORE_DESC"),
            Pair("Trending", "TRENDING_DESC"),
            Pair("Favorites", "FAVOURITES_DESC"),
            Pair("Latest", "START_DATE_DESC"),
            Pair("Oldest", "START_DATE"),
            Pair("Title A-Z", "TITLE_ROMAJI"),
            Pair("Title Z-A", "TITLE_ROMAJI_DESC"),
        )

        val GENRES = arrayOf(
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Comedy", "Comedy"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Harem", "Harem"),
            Pair("Horror", "Horror"),
            Pair("Isekai", "Isekai"),
            Pair("Mahou Shoujo", "Mahou Shoujo"),
            Pair("Mecha", "Mecha"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Sports", "Sports"),
            Pair("Supernatural", "Supernatural"),
            Pair("Thriller", "Thriller"),
        )

        val YEARS = arrayOf(ALL) + (2026 downTo 1940).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val SEASONS = arrayOf(
            ALL,
            Pair("Winter", "WINTER"),
            Pair("Spring", "SPRING"),
            Pair("Summer", "SUMMER"),
            Pair("Fall", "FALL"),
        )

        val STATUS = arrayOf(
            ALL,
            Pair("Airing", "RELEASING"),
            Pair("Finished", "FINISHED"),
            Pair("Not Yet Aired", "NOT_YET_RELEASED"),
            Pair("Cancelled", "CANCELLED"),
        )

        val FORMATS = arrayOf(
            Pair("TV", "TV"),
            Pair("TV Short", "TV_SHORT"),
            Pair("Movie", "MOVIE"),
            Pair("Special", "SPECIAL"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Music", "MUSIC"),
        )
    }
}
