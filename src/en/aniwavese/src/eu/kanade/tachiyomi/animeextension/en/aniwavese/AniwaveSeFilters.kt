package eu.kanade.tachiyomi.animeextension.en.aniwavese

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object AniwaveSeFilters {
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

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String = this.filterIsInstance<R>().joinToString("") {
        (it as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R = this.filterIsInstance<R>().first()

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
        name: String,
    ): String = (this.getFirst<R>() as CheckBoxFilterList).state
        .mapNotNull { checkbox ->
            if (checkbox.state) {
                options.find { it.first == checkbox.name }!!.second
            } else {
                null
            }
        }.joinToString("&$name[]=").let {
            if (it.isBlank()) {
                ""
            } else {
                "&$name[]=$it"
            }
        }

    class SortFilter : QueryPartFilter("Sort order", NineAnimeFiltersData.SORT)

    private class GenreModeFilter(state: Boolean = false) : AnimeFilter.CheckBox("Must have all selected genres", state) {
        fun asQueryPart(): String = if (state) "&genre_mode=and" else ""
    }

    class GenreFilter :
        CheckBoxFilterList(
            "Genre",
            NineAnimeFiltersData.GENRE.map { CheckBoxVal(it.first, false) },
        )

    class CountryFilter :
        CheckBoxFilterList(
            "Country",
            NineAnimeFiltersData.COUNTRY.map { CheckBoxVal(it.first, false) },
        )

    class SeasonFilter :
        CheckBoxFilterList(
            "Season",
            NineAnimeFiltersData.SEASON.map { CheckBoxVal(it.first, false) },
        )

    class YearFilter :
        CheckBoxFilterList(
            "Year",
            NineAnimeFiltersData.YEAR.map { CheckBoxVal(it.first, false) },
        )

    class TypeFilter :
        CheckBoxFilterList(
            "Type",
            NineAnimeFiltersData.TYPE.map { CheckBoxVal(it.first, false) },
        )

    class StatusFilter :
        CheckBoxFilterList(
            "Status",
            NineAnimeFiltersData.STATUS.map { CheckBoxVal(it.first, false) },
        )

    class LanguageFilter :
        CheckBoxFilterList(
            "Language",
            NineAnimeFiltersData.LANGUAGE.map { CheckBoxVal(it.first, false) },
        )

    class RatingFilter :
        CheckBoxFilterList(
            "Rating",
            NineAnimeFiltersData.RATING.map { CheckBoxVal(it.first, false) },
        )

    val FILTER_LIST get() = AnimeFilterList(
        // SortFilter(),
        GenreFilter(),
        GenreModeFilter(),
        AnimeFilter.Separator(),
        CountryFilter(),
        SeasonFilter(),
        YearFilter(),
        TypeFilter(),
        StatusFilter(),
        LanguageFilter(),
        RatingFilter(),
    )

    data class FilterSearchParams(
        val sort: String = "",
        val genreMode: String = "",
        val genre: String = "",
        val country: String = "",
        val season: String = "",
        val year: String = "",
        val type: String = "",
        val status: String = "",
        val language: String = "",
        val rating: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<SortFilter>(),
            filters.filterIsInstance<GenreModeFilter>().firstOrNull()?.asQueryPart() ?: "",
            filters.parseCheckbox<GenreFilter>(NineAnimeFiltersData.GENRE, "genre"),
            filters.parseCheckbox<CountryFilter>(NineAnimeFiltersData.COUNTRY, "country"),
            filters.parseCheckbox<SeasonFilter>(NineAnimeFiltersData.SEASON, "season"),
            filters.parseCheckbox<YearFilter>(NineAnimeFiltersData.YEAR, "year"),
            filters.parseCheckbox<TypeFilter>(NineAnimeFiltersData.TYPE, "type"),
            filters.parseCheckbox<StatusFilter>(NineAnimeFiltersData.STATUS, "status"),
            filters.parseCheckbox<LanguageFilter>(NineAnimeFiltersData.LANGUAGE, "language"),
            filters.parseCheckbox<RatingFilter>(NineAnimeFiltersData.RATING, "rating"),
        )
    }

    private object NineAnimeFiltersData {
        val SORT = arrayOf(
            Pair("Most relevance", "most_relevance"),
            Pair("Recently updated", "recently_updated"),
            Pair("Recently added", "recently_added"),
            Pair("Release date", "release_date"),
            Pair("Trending", "trending"),
            Pair("Name A-Z", "title_az"),
            Pair("Scores", "scores"),
            Pair("MAL scores", "mal_scores"),
            Pair("Most watched", "most_watched"),
            Pair("Most favourited", "most_favourited"),
            Pair("Number of episodes", "number_of_episodes"),
        )

        val GENRE = arrayOf(
            Pair("Action", "1"),
            Pair("Adventure", "2"),
            Pair("Avant Garde", "2262888"),
            Pair("Boys Love", "2262603"),
            Pair("Comedy", "4"),
            Pair("Demons", "4424081"),
            Pair("Drama", "7"),
            Pair("Ecchi", "8"),
            Pair("Fantasy", "9"),
            Pair("Girls Love", "2263743"),
            Pair("Gourmet", "2263289"),
            Pair("Harem", "11"),
            Pair("Horror", "14"),
            Pair("Isekai", "3457284"),
            Pair("Iyashikei", "4398552"),
            Pair("Josei", "15"),
            Pair("Kids", "16"),
            Pair("Magic", "4424082"),
            Pair("Mahou Shoujo", "3457321"),
            Pair("Martial Arts", "18"),
            Pair("Mecha", "19"),
            Pair("Military", "20"),
            Pair("Music", "21"),
            Pair("Mystery", "22"),
            Pair("Parody", "23"),
            Pair("Psychological", "25"),
            Pair("Reverse Harem", "4398403"),
            Pair("Romance", "26"),
            Pair("School", "28"),
            Pair("Sci-Fi", "29"),
            Pair("Seinen", "30"),
            Pair("Shoujo", "31"),
            Pair("Shounen", "33"),
            Pair("Slice of Life", "35"),
            Pair("Space", "36"),
            Pair("Sports", "37"),
            Pair("Super Power", "38"),
            Pair("Supernatural", "39"),
            Pair("Suspense", "2262590"),
            Pair("Thriller", "40"),
            Pair("Vampire", "41"),
        )

        val COUNTRY = arrayOf(
            Pair("China", "120823"),
            Pair("Japan", "120822"),
        )

        val SEASON = arrayOf(
            Pair("Fall", "fall"),
            Pair("Summer", "summer"),
            Pair("Spring", "spring"),
            Pair("Winter", "winter"),
            Pair("Unknown", "unknown"),
        )

        val YEAR = (Calendar.getInstance().get(Calendar.YEAR) downTo 2003)
            .map {
                Pair(it.toString(), it.toString())
            }.toTypedArray() +
            arrayOf(
                Pair("2000s", "2000s"),
                Pair("1990s", "1990s"),
                Pair("1980s", "1980s"),
                Pair("1970s", "1970s"),
                Pair("1960s", "1960s"),
                Pair("1950s", "1950s"),
                Pair("1940s", "1940s"),
                Pair("1930s", "1930s"),
                Pair("1920s", "1920s"),
                Pair("1910s", "1910s"),
            )

        val TYPE = arrayOf(
            Pair("Movie", "movie"),
            Pair("TV", "tv"),
            Pair("OVA", "ova"),
            Pair("ONA", "ona"),
            Pair("Special", "special"),
            Pair("Music", "music"),
        )

        val STATUS = arrayOf(
            Pair("Not Yet Aired", "info"),
            Pair("Releasing", "releasing"),
            Pair("Completed", "completed"),
        )

        val LANGUAGE = arrayOf(
            Pair("Sub and Dub", "subdub"),
            Pair("Sub", "sub"),
            Pair("S-Sub", "s-sub"),
            Pair("Dub", "dub"),
        )

        val RATING = arrayOf(
            Pair("G - All Ages", "g"),
            Pair("PG - Children", "pg"),
            Pair("PG 13 - Teens 13 and Older", "pg_13"),
            Pair("R - 17+, Violence & Profanity", "r"),
            Pair("R+ - Profanity & Mild Nudity", "r+"),
            Pair("Rx - Hentai", "rx"),
        )
    }
}
