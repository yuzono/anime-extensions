package eu.kanade.tachiyomi.multisrc.anikototheme

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import okhttp3.HttpUrl
import java.util.Calendar

object AnikotoThemeFilters {

    // ============================== Base Classes ==============================

    open class QueryPartFilter(
        displayName: String,
        val vals: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(
        name: String,
        val vals: List<Pair<String, String>>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>(
        name,
        vals.map { CheckBoxVal(it.first) },
    ) {
        fun toQueryPart(): List<String> = state
            .filter { it.state }
            .mapNotNull { checkbox ->
                vals.find { it.first == checkbox.name }?.second
            }
    }

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    // ============================== Filter Instances ==============================

    class SortFilter : QueryPartFilter("Sort order", AniKotoThemeFiltersData.SORT)

    class GenreFilter : CheckBoxFilterList("Genre", AniKotoThemeFiltersData.GENRE)

    class SeasonFilter : CheckBoxFilterList("Season", AniKotoThemeFiltersData.SEASON)

    class YearFilter : CheckBoxFilterList("Year", AniKotoThemeFiltersData.YEAR)

    class TypeFilter : CheckBoxFilterList("Type", AniKotoThemeFiltersData.TYPE)

    class StatusFilter : CheckBoxFilterList("Status", AniKotoThemeFiltersData.STATUS)

    class LanguageFilter : CheckBoxFilterList("Language", AniKotoThemeFiltersData.LANGUAGE)

    class RatingFilter : CheckBoxFilterList("Rating", AniKotoThemeFiltersData.RATING)

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

    // ============================== Search Parameters ==============================

    data class FilterSearchParams(
        val sort: String = "",
        val genres: List<String> = emptyList(),
        val seasons: List<String> = emptyList(),
        val years: List<String> = emptyList(),
        val types: List<String> = emptyList(),
        val statuses: List<String> = emptyList(),
        val languages: List<String> = emptyList(),
        val ratings: List<String> = emptyList(),
    )

    fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toQueryPart() ?: "",
            genres = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
            seasons = filters.filterIsInstance<SeasonFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
            years = filters.filterIsInstance<YearFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
            types = filters.filterIsInstance<TypeFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
            statuses = filters.filterIsInstance<StatusFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
            languages = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
            ratings = filters.filterIsInstance<RatingFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
        )
    }

    // ============================== URL Builder Helpers ==============================

    fun HttpUrl.Builder.addQueryParameterIfNotEmpty(query: String, value: String): HttpUrl.Builder {
        if (value.isNotEmpty()) addQueryParameter(query, value)
        return this
    }

    fun HttpUrl.Builder.addListQueryParameter(query: String, values: List<String>): HttpUrl.Builder {
        values.forEach { addQueryParameter("$query[]", it) }
        return this
    }

    // ============================== Data ==============================

    private object AniKotoThemeFiltersData {
        val SORT = listOf(
            Pair("Default", "default"),
            Pair("Latest Updated", "latest-updated"),
            Pair("Latest Added", "latest-added"),
            Pair("Score", "score"),
            Pair("Name A-Z", "name-az"),
            Pair("Release Date", "release-date"),
            Pair("Most Viewed", "most-viewed"),
            Pair("Number of episodes", "number_of_episodes"),
        )

        val GENRE = listOf(
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

        val SEASON = listOf(
            Pair("Fall", "fall"),
            Pair("Summer", "summer"),
            Pair("Spring", "spring"),
            Pair("Winter", "winter"),
        )

        val YEAR = buildList {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            addAll((currentYear + 1 downTo 1980).map { Pair(it.toString(), it.toString()) })
        }

        val TYPE = listOf(
            Pair("Movie", "Movie"),
            Pair("TV", "TV"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Special", "Special"),
            Pair("Music", "Music"),
        )

        val STATUS = listOf(
            Pair("Finished Airing", "finished-airing"),
            Pair("Currently Airing", "currently-airing"),
            Pair("Not Yet Aired", "not-yet-aired"),
        )

        val LANGUAGE = listOf(
            Pair("Sub", "sub"),
            Pair("Dub", "dub"),
        )

        val RATING = listOf(
            Pair("PG - Children", "PG"),
            Pair("PG 13 - Teens 13 and Older", "PG-13"),
            Pair("G - All Ages", "G"),
            Pair("R - 17+, Violence & Profanity", "R"),
            Pair("R+ - Profanity & Mild Nudity", "R+"),
            Pair("Rx - Hentai", "Rx"),
        )
    }
}
