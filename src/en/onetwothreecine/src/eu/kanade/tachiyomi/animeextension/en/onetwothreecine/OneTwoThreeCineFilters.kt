package eu.kanade.tachiyomi.animeextension.en.onetwothreecine

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object OneTwoThreeCineFilters {

    data class SearchParameters(
        var types: List<String> = emptyList(),
        var genres: List<String> = emptyList(),
        var genreMode: String = "and",
        var countries: List<String> = emptyList(),
        var countryMode: String = "or",
        var years: List<String> = emptyList(),
        var qualities: List<String> = emptyList(),
        var sort: String = "",
    )

    fun getSearchParameters(filters: AnimeFilterList): SearchParameters {
        val params = SearchParameters()
        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> params.types = filter.selectedValues
                is GenreFilter -> params.genres = filter.selectedValues
                is GenreModeFilter -> params.genreMode = if (filter.state == 1) "or" else "and"
                is CountryFilter -> params.countries = filter.selectedValues
                is CountryModeFilter -> params.countryMode = if (filter.state == 1) "or" else "and"
                is YearFilter -> params.years = filter.selectedValues
                is QualityFilter -> params.qualities = filter.selectedValues
                is SortFilter -> params.sort = filter.selectedValue
                else -> {}
            }
        }
        return params
    }

    // ===================== Filter Base Classes =====================

    internal open class CheckBoxFilterList(
        name: String,
        options: List<Pair<String, String>>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>(
        name,
        options.map { CheckBoxVal(it.first, it.second) },
    ) {
        private class CheckBoxVal(name: String, val value: String) : CheckBox(name)
        val selectedValues: List<String>
            get() = state.filter { it.state }.mapNotNull { (it as? CheckBoxVal)?.value }
    }

    internal open class QueryPartFilter(
        displayName: String,
        private val options: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        options.map { it.first }.toTypedArray(),
    ) {
        val selectedValue: String
            get() = if (state > 0) options[state].second else ""
    }

    // ====================== Concrete Filters ======================

    private class TypeFilter : CheckBoxFilterList("Type", TYPES)
    private class GenreFilter : CheckBoxFilterList("Genre", GENRES)
    private class CountryFilter : CheckBoxFilterList("Country", COUNTRIES)
    private class YearFilter : CheckBoxFilterList("Year", YEARS)
    private class QualityFilter : CheckBoxFilterList("Quality", QUALITIES)
    private class SortFilter : QueryPartFilter("Sort By", SORT_BY)

    class GenreModeFilter :
        AnimeFilter.Select<String>(
            "Genre Inclusion Mode",
            arrayOf("And (all must match)", "Or (any can match)"),
        )

    class CountryModeFilter :
        AnimeFilter.Select<String>(
            "Country Inclusion Mode",
            arrayOf("And (all must match)", "Or (any can match)"),
        )

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            TypeFilter(),
            GenreModeFilter(),
            GenreFilter(),
            CountryModeFilter(),
            CountryFilter(),
            YearFilter(),
            QualityFilter(),
            SortFilter(),
        )

    // ============================ Data =============================

    private val TYPES = listOf(
        "Movie" to "movie",
        "TV-Shows" to "tv",
    )

    private val GENRES = listOf(
        "Action" to "14", "Adult" to "15265", "Adventure" to "109",
        "Animation" to "404", "Biography" to "312", "Comedy" to "1",
        "Costume" to "50202", "Crime" to "126", "Documentary" to "92",
        "Drama" to "12", "Family" to "78", "Fantasy" to "53",
        "Film-Noir" to "1779", "Game-Show" to "966", "History" to "239",
        "Horror" to "2", "Kungfu" to "67893", "Music" to "99",
        "Musical" to "1809", "Mystery" to "154", "News" to "1515",
        "Reality" to "6774", "Reality-TV" to "726", "Romance" to "44",
        "Sci-Fi" to "162", "Science Fiction" to "219174",
        "Short" to "405", "Sport" to "79",
        "Talk" to "92400", "Talk-Show" to "7024", "Thriller" to "13",
        "TV Movie" to "18067", "TV Show" to "11185",
        "War" to "436", "War & Politics" to "218204",
        "Western" to "1443",
    )

    private val COUNTRIES = listOf(
        "Argentina" to "3388", "Australia" to "30", "Austria" to "1791",
        "Belgium" to "111", "Brazil" to "616", "Canada" to "64",
        "China" to "350", "Colombia" to "11332", "Czech Republic" to "5187",
        "Denmark" to "375", "Finland" to "3356", "France" to "16",
        "Germany" to "127", "Hong Kong" to "351", "Hungary" to "5042",
        "India" to "110", "Ireland" to "225", "Israel" to "1617",
        "Italy" to "163", "Japan" to "291", "Luxembourg" to "8087",
        "Mexico" to "1727", "Netherlands" to "867", "New Zealand" to "1616",
        "Nigeria" to "1618", "Norway" to "3357", "Philippines" to "4141",
        "Poland" to "5600", "Romania" to "5730", "Russia" to "6646",
        "South Africa" to "1541", "South Korea" to "360", "Spain" to "240",
        "Sweden" to "1728", "Switzerland" to "2521", "Taiwan" to "3564",
        "Thailand" to "9360", "Turkey" to "881", "United Kingdom" to "15",
        "United States" to "3",
    )

    private val YEARS = listOf(
        "2026" to "2026", "2025" to "2025", "2024" to "2024",
        "2023" to "2023", "2022" to "2022", "2021" to "2021",
        "2020" to "2020", "2019" to "2019", "2018" to "2018",
        "2017" to "2017", "2016" to "2016", "Older" to "older",
    )

    private val QUALITIES = listOf(
        "HD" to "HD",
        "HDrip" to "HDrip",
        "SD" to "SD",
        "TS" to "TS",
        "CAM" to "CAM",
    )

    private val SORT_BY = listOf(
        "Most relevant" to "most_relevance",
        "Updated date" to "updated_date",
        "Added date" to "added_date",
        "Release date" to "release_date",
        "Trending" to "trending",
        "Name A-Z" to "title_az",
        "Average score" to "score",
        "IMDb" to "imdb",
        "Most viewed" to "most_viewed",
        "Most followed" to "most_followed",
    )
}
