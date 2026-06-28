package eu.kanade.tachiyomi.animeextension.en.anilist

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, pairs: Array<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { CheckBoxVal(it.first, false) })

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String = (getFirst<R>() as QueryPartFilter).toQueryPart()

    private inline fun <reified R> AnimeFilterList.getFirst(): R = first { it is R } as R

    private inline fun <reified R> AnimeFilterList.parseCheckboxList(
        options: Array<Pair<String, String>>,
    ): List<String> = (getFirst<R>() as CheckBoxFilterList).state
        .filter { it.state }
        .map { checkBox -> options.find { it.first == checkBox.name }!!.second }
        .filter(String::isNotBlank)

    private inline fun <reified R> AnimeFilterList.getSort(): String {
        val state = (getFirst<R>() as AnimeFilter.Sort).state ?: return ""
        val index = state.index
        val suffix = if (state.ascending) "" else "_DESC"
        return AniListFiltersData.SORT_LIST[index].second + suffix
    }

    class AniListListFilter :
        AnimeFilter.Select<String>(
            "AniList Collection Import",
            arrayOf(
                "Disabled (Standard Search)",
                "All Lists",
                "Watching",
                "Rewatching",
                "Completed",
                "Paused",
                "Dropped",
                "Planning",
            ),
        ) {
        fun getStatus(): String? = when (state) {
            2 -> "CURRENT"
            3 -> "REPEATING"
            4 -> "COMPLETED"
            5 -> "PAUSED"
            6 -> "DROPPED"
            7 -> "PLANNING"
            else -> null
        }

        fun isActive(): Boolean = state > 0
    }

    class GenreFilter : CheckBoxFilterList("Genres", AniListFiltersData.GENRE_LIST)
    class YearFilter : QueryPartFilter("Year", AniListFiltersData.YEAR_LIST)
    class SeasonFilter : QueryPartFilter("Season", AniListFiltersData.SEASON_LIST)
    class FormatFilter : CheckBoxFilterList("Format", AniListFiltersData.FORMAT_LIST)
    class StatusFilter : QueryPartFilter("Airing Status", AniListFiltersData.STATUS_LIST)
    class CountryFilter : QueryPartFilter("Country Of Origin", AniListFiltersData.COUNTRY_LIST)

    class SortFilter :
        AnimeFilter.Sort(
            "Sort",
            AniListFiltersData.SORT_LIST.map { it.first }.toTypedArray(),
            Selection(1, false),
        )

    val FILTER_LIST get() = AnimeFilterList(
        AniListListFilter(),
        AnimeFilter.Header(""),
        GenreFilter(),
        YearFilter(),
        SeasonFilter(),
        FormatFilter(),
        StatusFilter(),
        CountryFilter(),
        SortFilter(),
    )

    class FilterSearchParams(
        val genres: List<String> = emptyList(),
        val year: String = "",
        val season: String = "",
        val format: List<String> = emptyList(),
        val status: String = "",
        val country: String = "",
        val sort: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckboxList<GenreFilter>(AniListFiltersData.GENRE_LIST),
            filters.asQueryPart<YearFilter>(),
            filters.asQueryPart<SeasonFilter>(),
            filters.parseCheckboxList<FormatFilter>(AniListFiltersData.FORMAT_LIST),
            filters.asQueryPart<StatusFilter>(),
            filters.asQueryPart<CountryFilter>(),
            filters.getSort<SortFilter>(),
        )
    }

    private object AniListFiltersData {
        val GENRE_LIST = arrayOf(
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Comedy", "Comedy"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Horror", "Horror"),
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

        val YEAR_LIST = arrayOf(
            Pair("<Select>", ""), Pair("2026", "2026"), Pair("2025", "2025"),
            Pair("2024", "2024"), Pair("2023", "2023"), Pair("2022", "2022"),
            Pair("2021", "2021"), Pair("2020", "2020"), Pair("2019", "2019"),
            Pair("2018", "2018"), Pair("2017", "2017"), Pair("2016", "2016"),
            Pair("2015", "2015"), Pair("2014", "2014"), Pair("2013", "2013"),
            Pair("2012", "2012"), Pair("2011", "2011"), Pair("2010", "2010"),
            Pair("2009", "2009"), Pair("2008", "2008"), Pair("2007", "2007"),
            Pair("2006", "2006"), Pair("2005", "2005"), Pair("2004", "2004"),
            Pair("2003", "2003"), Pair("2002", "2002"), Pair("2001", "2001"),
            Pair("2000", "2000"), Pair("1999", "1999"), Pair("1998", "1998"),
        )

        val SEASON_LIST = arrayOf(
            Pair("<Select>", ""),
            Pair("Winter", "WINTER"),
            Pair("Spring", "SPRING"),
            Pair("Summer", "SUMMER"),
            Pair("Fall", "FALL"),
        )

        val FORMAT_LIST = arrayOf(
            Pair("TV Show", "TV"),
            Pair("Movie", "MOVIE"),
            Pair("TV Short", "TV_SHORT"),
            Pair("Special", "SPECIAL"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Music", "MUSIC"),
        )

        val STATUS_LIST = arrayOf(
            Pair("<Select>", ""),
            Pair("Airing", "RELEASING"),
            Pair("Finished", "FINISHED"),
            Pair("Not Yet Aired", "NOT_YET_RELEASED"),
            Pair("Cancelled", "CANCELLED"),
        )

        val COUNTRY_LIST = arrayOf(
            Pair("<Select>", ""),
            Pair("Japan", "JP"),
            Pair("South Korea", "KR"),
            Pair("China", "CN"),
            Pair("Taiwan", "TW"),
        )

        val SORT_LIST = arrayOf(
            Pair("Title", "TITLE_ENGLISH"),
            Pair("Popularity", "POPULARITY"),
            Pair("Average Score", "SCORE"),
            Pair("Trending", "TRENDING"),
            Pair("Favorites", "FAVOURITES"),
            Pair("Date Added", "ID"),
            Pair("Release Date", "START_DATE"),
        )
    }
}
