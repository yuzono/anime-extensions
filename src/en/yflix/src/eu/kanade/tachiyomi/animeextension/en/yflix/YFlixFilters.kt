package eu.kanade.tachiyomi.animeextension.en.yflix

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import okhttp3.HttpUrl

object YFlixFilters {

    fun getFilters(filters: AnimeFilterList) = filters.filterIsInstance<Filter>()

    interface Filter {
        fun addQueryParameters(builder: HttpUrl.Builder)
    }

    internal open class QueryPartFilter(
        displayName: String,
        private val param: String,
        private val options: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        options.map { it.first }.toTypedArray(),
    ),
        Filter {
        override fun addQueryParameters(builder: HttpUrl.Builder) {
            if (state > 0) {
                builder.addQueryParameter(param, options[state].second)
            }
        }
    }

    internal open class CheckBoxFilterList(
        name: String,
        private val param: String,
        private val options: List<Pair<String, String>>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>(
        name,
        options.map { CheckBoxVal(it.first, false) },
    ),
        Filter {

        private class CheckBoxVal(name: String, state: Boolean = false) : CheckBox(name, state)

        override fun addQueryParameters(builder: HttpUrl.Builder) {
            state
                .filter { it.state }
                .forEach { checkbox ->
                    options.find { it.first == checkbox.name }?.second?.let {
                        builder.addQueryParameter("$param[]", it)
                    }
                }
        }
    }

    internal class TypesFilter : CheckBoxFilterList("Type", "type", YFlixFiltersData.TYPES)
    internal class QualitiesFilter : CheckBoxFilterList("Quality", "quality", YFlixFiltersData.QUALITIES)
    internal class YearsFilter : CheckBoxFilterList("Released", "year", YFlixFiltersData.YEARS)
    internal class GenresFilter : CheckBoxFilterList("Genre", "genre", YFlixFiltersData.GENRES)
    internal class CountriesFilter : CheckBoxFilterList("Country", "country", YFlixFiltersData.COUNTRIES)
    internal class SortByFilter : QueryPartFilter("Sort", "sort", YFlixFiltersData.SORT_BY)

    val FILTER_LIST
        get() = AnimeFilterList(
            TypesFilter(),
            QualitiesFilter(),
            YearsFilter(),
            GenresFilter(),
            CountriesFilter(),
            SortByFilter(),
        )

    private object YFlixFiltersData {
        val TYPES = listOf(
            Pair("Movie", "movie"),
            Pair("TV-Shows", "tv"),
        )

        val QUALITIES = listOf(
            Pair("HD", "HD"),
            Pair("HDRip", "HDRip"),
            Pair("SD", "SD"),
            Pair("TS", "TS"),
            Pair("CAM", "CAM"),
        )

        val YEARS = listOf(
            Pair("2026", "2026"),
            Pair("2025", "2025"),
            Pair("2024", "2024"),
            Pair("2023", "2023"),
            Pair("2022", "2022"),
            Pair("2021", "2021"),
            Pair("2020", "2020"),
            Pair("2019", "2019"),
            Pair("2018", "2018"),
            Pair("2017", "2017"),
            Pair("2016", "2016"),
            Pair("2015", "2015"),
            Pair("Older", "older"),
        )

        val SORT_BY = listOf(
            Pair("Default", ""),
            Pair("Updated date", "updated_date"),
            Pair("Added date", "added_date"),
            Pair("Release date", "release_date"),
            Pair("Trending", "trending"),
            Pair("Name A-Z", "title_az"),
            Pair("Average score", "score"),
            Pair("IMDb", "imdb"),
            Pair("Total views", "total_views"),
            Pair("Total bookmarks", "total_bookmarks"),
        )

        val GENRES = listOf(
            Pair("Action", "14"),
            Pair("Adult", "15265"),
            Pair("Adventure", "109"),
            Pair("Animation", "404"),
            Pair("Biography", "312"),
            Pair("Comedy", "1"),
            Pair("Costume", "50202"),
            Pair("Crime", "126"),
            Pair("Documentary", "92"),
            Pair("Drama", "12"),
            Pair("Family", "78"),
            Pair("Fantasy", "53"),
            Pair("Film-Noir", "1779"),
            Pair("Game-Show", "966"),
            Pair("History", "239"),
            Pair("Horror", "2"),
            Pair("Kungfu", "67893"),
            Pair("Music", "99"),
            Pair("Musical", "1809"),
            Pair("Mystery", "154"),
            Pair("News", "1515"),
            Pair("Reality", "6774"),
            Pair("Reality-TV", "726"),
            Pair("Romance", "44"),
            Pair("Sci-Fi", "162"),
            Pair("Short", "405"),
            Pair("Sport", "79"),
            Pair("Talk", "92400"),
            Pair("Talk-Show", "7024"),
            Pair("Thriller", "13"),
            Pair("TV Movie", "18067"),
            Pair("TV Show", "11185"),
            Pair("War", "436"),
            Pair("Western", "1443"),
        )

        val COUNTRIES = listOf(
            Pair("Argentina", "3388"),
            Pair("Australia", "30"),
            Pair("Austria", "1791"),
            Pair("Belgium", "111"),
            Pair("Brazil", "616"),
            Pair("Canada", "64"),
            Pair("China", "350"),
            Pair("Colombia", "11332"),
            Pair("Czech Republic", "5187"),
            Pair("Denmark", "375"),
            Pair("Finland", "3356"),
            Pair("France", "16"),
            Pair("Germany", "127"),
            Pair("Hong Kong", "351"),
            Pair("Hungary", "5042"),
            Pair("India", "110"),
            Pair("Ireland", "225"),
            Pair("Italy", "163"),
            Pair("Japan", "291"),
            Pair("Luxembourg", "8087"),
            Pair("Mexico", "1727"),
            Pair("Netherlands", "867"),
            Pair("New Zealand", "1616"),
            Pair("Nigeria", "1618"),
            Pair("Norway", "3357"),
            Pair("Philippines", "4141"),
            Pair("Poland", "5600"),
            Pair("Romania", "5730"),
            Pair("Russia", "6646"),
            Pair("South Africa", "1541"),
            Pair("South Korea", "360"),
            Pair("Spain", "240"),
            Pair("Sweden", "1728"),
            Pair("Switzerland", "2521"),
            Pair("Taiwan", "3564"),
            Pair("Thailand", "9360"),
            Pair("Turkey", "881"),
            Pair("United Kingdom", "15"),
            Pair("United States", "3"),
        )
    }
}
