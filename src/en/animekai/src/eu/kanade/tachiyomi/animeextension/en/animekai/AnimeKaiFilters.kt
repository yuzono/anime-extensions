package eu.kanade.tachiyomi.animeextension.en.animekai

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.CheckBox
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import okhttp3.HttpUrl

object AnimeKaiFilters {

    internal open class QueryPartFilter(
        displayName: String,
        private val param: String,
        private val options: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        options.map { it.first }.toTypedArray(),
    ) {
        fun addQueryParameters(builder: HttpUrl.Builder) {
            builder.addQueryParameter(param, options[state].second)
        }
    }

    internal open class CheckBoxFilterList(
        name: String,
        private val param: String,
        private val options: List<Pair<String, String>>,
    ) : AnimeFilter.Group<CheckBox>(
        name,
        options.map { CheckBoxVal(it.first, false) },
    ) {
        fun addQueryParameters(builder: HttpUrl.Builder) {
            state
                .filter { it.state }
                .forEach { checkbox ->
                    options.find { it.first == checkbox.name }?.second?.let {
                        builder.addQueryParameter("$param[]", it)
                    }
                }
        }
    }
    private class CheckBoxVal(name: String, state: Boolean = false) : CheckBox(name, state)

    internal open class TriStateFilterList(
        name: String,
        private val param: String,
        private val options: List<Pair<String, String>>,
    ) : AnimeFilter.Group<TriState>(
        name,
        options.map { TriFilterVal(it.first) },
    ) {
        fun addQueryParameters(builder: HttpUrl.Builder) {
            state
                .filterNot { it.isIgnored() }
                .forEach { tristate ->
                    options.find { it.first == tristate.name }?.second?.let {
                        val state = if (tristate.state == TriState.STATE_INCLUDE) "" else "-"
                        builder.addQueryParameter("$param[]", "$state$it")
                    }
                }
        }
    }
    class TriFilterVal(name: String) : TriState(name)

    inline fun <reified R> AnimeFilterList.getFirstOrNull(): R? = this.filterIsInstance<R>().firstOrNull()

    internal class TypesFilter : CheckBoxFilterList("Types", "type", AnimeKaiFiltersData.TYPES)
    internal class GenresFilter : TriStateFilterList("Genres", "genre", AnimeKaiFiltersData.GENRES)
    internal class StatusFilter : CheckBoxFilterList("Status", "status", AnimeKaiFiltersData.STATUS)

    internal class SortByFilter : QueryPartFilter("Sort By", "sort", AnimeKaiFiltersData.SORT_BY)

    internal class SeasonsFilter : CheckBoxFilterList("Season", "season", AnimeKaiFiltersData.SEASONS)
    internal class YearsFilter : CheckBoxFilterList("Year", "year", AnimeKaiFiltersData.YEARS)
    internal class RatingFilter : CheckBoxFilterList("Rating", "rating", AnimeKaiFiltersData.RATINGS)
    internal class CountriesFilter : CheckBoxFilterList("Origin Country", "country", AnimeKaiFiltersData.COUNTRIES)
    internal class LanguagesFilter : CheckBoxFilterList("Language", "language", AnimeKaiFiltersData.LANGUAGES)

    val FILTER_LIST get() = AnimeFilterList(
        TypesFilter(),
        GenresFilter(),
        StatusFilter(),
        SortByFilter(),
        SeasonsFilter(),
        YearsFilter(),
        RatingFilter(),
        CountriesFilter(),
        LanguagesFilter(),
    )

    private object AnimeKaiFiltersData {
        val COUNTRIES = listOf(
            Pair("China", "2"),
            Pair("Japan", "11"),
        )

        val LANGUAGES = listOf(
            Pair("Hard Sub", "sub"),
            Pair("Soft Sub", "softsub"),
            Pair("Dub", "dub"),
            Pair("Sub & Dub", "subdub"),
        )

        val SEASONS = listOf(
            Pair("Fall", "fall"),
            Pair("Summer", "summer"),
            Pair("Spring", "spring"),
            Pair("Winter", "winter"),
            Pair("Unknown", "unknown"),
        )

        val YEARS = listOf(
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
            Pair("2014", "2014"),
            Pair("2013", "2013"),
            Pair("2012", "2012"),
            Pair("2011", "2011"),
            Pair("2010", "2010"),
            Pair("2009", "2009"),
            Pair("2008", "2008"),
            Pair("2007", "2007"),
            Pair("2006", "2006"),
            Pair("2005", "2005"),
            Pair("2004", "2004"),
            Pair("2003", "2003"),
            Pair("2002", "2002"),
            Pair("2001", "2001"),
            Pair("2000", "2000"),
            Pair("1990s", "1990s"),
            Pair("1980s", "1980s"),
            Pair("1970s", "1970s"),
            Pair("1960s", "1960s"),
            Pair("1950s", "1950s"),
            Pair("1940s", "1940s"),
            Pair("1930s", "1930s"),
            Pair("1920s", "1920s"),
            Pair("1910s", "1910s"),
            Pair("1900s", "1900s"),
        )

        val SORT_BY = listOf(
            Pair("Most relevant", "most_relevance"),
            Pair("Updated date", "updated_date"),
            Pair("Release date", "release_date"),
            Pair("End date", "end_date"),
            Pair("Added date", "added_date"),
            Pair("Trending", "trending"),
            Pair("Name A-Z", "title_az"),
            Pair("Average score", "avg_score"),
            Pair("MAL score", "mal_score"),
            Pair("Total views", "total_views"),
            Pair("Total bookmarks", "total_bookmarks"),
            Pair("Total episodes", "total_episodes"),
        )

        val STATUS = listOf(
            Pair("Releasing", "releasing"),
            Pair("Completed", "completed"),
        )

        val TYPES = listOf(
            Pair("Movie", "movie"),
            Pair("TV", "tv"),
            Pair("OVA", "ova"),
            Pair("ONA", "ona"),
            Pair("Special", "special"),
            Pair("Music", "music"),
        )

        val RATINGS = listOf(
            Pair("G - All Ages", "g"),
            Pair("PG - Children", "pg"),
            Pair("PG-13 - Teens 13 or older", "pg_13"),
            Pair("R - 17+, Violence & Profanity", "r"),
            Pair("R+ - Profanity & Mild Nudity", "r+"),
            Pair("Rx - Hentai", "rx"),
        )

        val GENRES = listOf(
            Pair("Action", "47"),
            Pair("Adventure", "1"),
            Pair("Avant Garde", "235"),
            Pair("Boys Love", "184"),
            Pair("Comedy", "7"),
            Pair("Demons", "127"),
            Pair("Drama", "66"),
            Pair("Ecchi", "8"),
            Pair("Fantasy", "34"),
            Pair("Girls Love", "926"),
            Pair("Gourmet", "436"),
            Pair("Harem", "196"),
            Pair("Horror", "421"),
            Pair("Isekai", "77"),
            Pair("Iyashikei", "225"),
            Pair("Josei", "555"),
            Pair("Kids", "35"),
            Pair("Magic", "78"),
            Pair("Mahou Shoujo", "857"),
            Pair("Martial Arts", "92"),
            Pair("Mecha", "219"),
            Pair("Military", "134"),
            Pair("Music", "27"),
            Pair("Mystery", "48"),
            Pair("Parody", "356"),
            Pair("Psychological", "240"),
            Pair("Reverse Harem", "798"),
            Pair("Romance", "145"),
            Pair("School", "9"),
            Pair("Sci-Fi", "36"),
            Pair("Seinen", "189"),
            Pair("Shoujo", "183"),
            Pair("Shounen", "37"),
            Pair("Slice of Life", "125"),
            Pair("Space", "220"),
            Pair("Sports", "10"),
            Pair("Super Power", "350"),
            Pair("Supernatural", "49"),
            Pair("Suspense", "322"),
            Pair("Thriller", "241"),
            Pair("Vampire", "126"),
        )
    }
}
