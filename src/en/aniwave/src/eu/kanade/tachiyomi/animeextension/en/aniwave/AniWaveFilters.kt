package eu.kanade.tachiyomi.animeextension.en.aniwave

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object AniWaveFilters {
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

    class SortFilter : QueryPartFilter("Sort order", AniWaveFiltersData.SORT)

    class GenreFilter :
        CheckBoxFilterList(
            "Genre",
            AniWaveFiltersData.GENRE.map { CheckBoxVal(it.first, false) },
        )

    class SeasonFilter :
        CheckBoxFilterList(
            "Season",
            AniWaveFiltersData.SEASON.map { CheckBoxVal(it.first, false) },
        )

    class YearFilter :
        CheckBoxFilterList(
            "Year",
            AniWaveFiltersData.YEAR.map { CheckBoxVal(it.first, false) },
        )

    class TypeFilter :
        CheckBoxFilterList(
            "Type",
            AniWaveFiltersData.TYPE.map { CheckBoxVal(it.first, false) },
        )

    class StatusFilter :
        CheckBoxFilterList(
            "Status",
            AniWaveFiltersData.STATUS.map { CheckBoxVal(it.first, false) },
        )

    class LanguageFilter :
        CheckBoxFilterList(
            "Language",
            AniWaveFiltersData.LANGUAGE.map { CheckBoxVal(it.first, false) },
        )

    class RatingFilter :
        CheckBoxFilterList(
            "Rating",
            AniWaveFiltersData.RATING.map { CheckBoxVal(it.first, false) },
        )

    val FILTER_LIST get() = AnimeFilterList(
        SortFilter(),
        GenreFilter(),
        SeasonFilter(),
        YearFilter(),
        TypeFilter(),
        StatusFilter(),
        LanguageFilter(),
        RatingFilter(),
    )

    data class FilterSearchParams(
        val sort: String = "",
        val genre: String = "",
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
            sort = filters.asQueryPart<SortFilter>(),
            genre = filters.parseCheckbox<GenreFilter>(AniWaveFiltersData.GENRE, "genre"),
            season = filters.parseCheckbox<SeasonFilter>(AniWaveFiltersData.SEASON, "season"),
            year = filters.parseCheckbox<YearFilter>(AniWaveFiltersData.YEAR, "year"),
            type = filters.parseCheckbox<TypeFilter>(AniWaveFiltersData.TYPE, "term_type"),
            status = filters.parseCheckbox<StatusFilter>(AniWaveFiltersData.STATUS, "status"),
            language = filters.parseCheckbox<LanguageFilter>(AniWaveFiltersData.LANGUAGE, "language"),
            rating = filters.parseCheckbox<RatingFilter>(AniWaveFiltersData.RATING, "rating"),
        )
    }

    private object AniWaveFiltersData {
        val SORT = arrayOf(
            Pair("Default", "default"),
            Pair("Latest Updated", "latest-updated"),
            Pair("Latest Added", "latest-added"),
            Pair("Score", "score"),
            Pair("Name A-Z", "name-az"),
            Pair("Release Date", "release-date"),
            Pair("Most Viewed", "most-viewed"),
            Pair("Number of episodes", "number_of_episodes"),
        )

        val GENRE = arrayOf(
            Pair("Action", "1"),
            Pair("Adventure", "2"),
            Pair("Cars", "538"),
            Pair("Comedy", "8"),
            Pair("Dementia", "453"),
            Pair("Demons", "119"),
            Pair("Drama", "62"),
            Pair("Ecchi", "214"),
            Pair("Fantasy", "3"),
            Pair("Game", "180"),
            Pair("Harem", "215"),
            Pair("Historical", "70"),
            Pair("Horror", "222"),
            Pair("Isekai", "74"),
            Pair("Josei", "404"),
            Pair("Kids", "46"),
            Pair("Magic", "203"),
            Pair("Martial Arts", "114"),
            Pair("Mecha", "123"),
            Pair("Military", "125"),
            Pair("Music", "242"),
            Pair("Mystery", "57"),
            Pair("Parody", "162"),
            Pair("Police", "136"),
            Pair("Psychological", "73"),
            Pair("Romance", "28"),
            Pair("Samurai", "163"),
            Pair("School", "14"),
            Pair("Sci-Fi", "12"),
            Pair("Seinen", "50"),
            Pair("Shoujo", "252"),
            Pair("Shoujo Ai", "235"),
            Pair("Shounen", "15"),
            Pair("Shounen Ai", "233"),
            Pair("Slice of Life", "35"),
            Pair("Space", "124"),
            Pair("Sports", "29"),
            Pair("Super Power", "16"),
            Pair("Supernatural", "9"),
            Pair("Thriller", "54"),
            Pair("unknown", "32"),
            Pair("Vampire", "58"),
        )

        val SEASON = arrayOf(
            Pair("Fall", "fall"),
            Pair("Summer", "summer"),
            Pair("Spring", "spring"),
            Pair("Winter", "winter"),
        )

        val YEAR = (Calendar.getInstance().get(Calendar.YEAR) + 1 downTo 1980)
            .map {
                Pair(it.toString(), it.toString())
            }.toTypedArray()

        val TYPE = arrayOf(
            Pair("Movie", "Movie"),
            Pair("TV", "TV"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Special", "Special"),
            Pair("Music", "Music"),
        )

        val STATUS = arrayOf(
            Pair("Finished Airing", "finished-airing"),
            Pair("Currently Airing", "currently-airing"),
            Pair("Not Yet Aired", "not-yet-aired"),
        )

        val LANGUAGE = arrayOf(
            Pair("Sub", "sub"),
            Pair("Dub", "dub"),
        )

        val RATING = arrayOf(
            Pair("PG - Children", "PG"),
            Pair("PG 13 - Teens 13 and Older", "PG-13"),
            Pair("G - All Ages", "G"),
            Pair("R - 17+, Violence & Profanity", "R"),
            Pair("R+ - Profanity & Mild Nudity", "R+"),
            Pair("Rx - Hentai", "Rx"),
        )
    }
}
