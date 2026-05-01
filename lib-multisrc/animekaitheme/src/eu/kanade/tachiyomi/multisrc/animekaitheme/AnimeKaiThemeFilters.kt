package eu.kanade.tachiyomi.multisrc.animekaitheme

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import okhttp3.HttpUrl
import java.util.Calendar
object AnimeKaiThemeFilters {

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

    open class TriStateFilterList(
        name: String,
        val vals: List<Pair<String, String>>,
    ) : AnimeFilter.Group<AnimeFilter.TriState>(
        name,
        vals.map { TriFilterVal(it.first) },
    ) {
        fun toQueryPart(): List<String> = state
            .filterNot { it.isIgnored() }
            .mapNotNull { tristate ->
                vals.find { it.first == tristate.name }?.second?.let {
                    val prefix = if (tristate.state == TriState.STATE_INCLUDE) "" else "-"
                    "$prefix$it"
                }
            }
    }

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)
    class TriFilterVal(name: String) : AnimeFilter.TriState(name)

    // ============================== Filter Instances ==============================

    class TypesFilter : CheckBoxFilterList("Types", AnimeKaiThemeFiltersData.TYPES)
    class GenresFilter : TriStateFilterList("Genres", AnimeKaiThemeFiltersData.GENRES)
    class StatusFilter : CheckBoxFilterList("Status", AnimeKaiThemeFiltersData.STATUS)
    class SortByFilter : QueryPartFilter("Sort By", AnimeKaiThemeFiltersData.SORT_BY)
    class SeasonsFilter : CheckBoxFilterList("Season", AnimeKaiThemeFiltersData.SEASONS)
    class YearsFilter : CheckBoxFilterList("Year", AnimeKaiThemeFiltersData.YEARS)
    class RatingFilter : CheckBoxFilterList("Rating", AnimeKaiThemeFiltersData.RATINGS)
    class CountriesFilter : CheckBoxFilterList("Origin Country", AnimeKaiThemeFiltersData.COUNTRIES)
    class LanguagesFilter : CheckBoxFilterList("Language", AnimeKaiThemeFiltersData.LANGUAGES)

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

    // ============================== Search Parameters ==============================

    data class FilterSearchParams(
        val types: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
        val statuses: List<String> = emptyList(),
        val sort: String = "",
        val seasons: List<String> = emptyList(),
        val years: List<String> = emptyList(),
        val ratings: List<String> = emptyList(),
        val countries: List<String> = emptyList(),
        val languages: List<String> = emptyList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            types = filters.filterIsInstance<TypesFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
            genres = filters.filterIsInstance<GenresFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
            statuses = filters.filterIsInstance<StatusFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
            sort = filters.filterIsInstance<SortByFilter>().firstOrNull()?.toQueryPart() ?: "",
            seasons = filters.filterIsInstance<SeasonsFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
            years = filters.filterIsInstance<YearsFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
            ratings = filters.filterIsInstance<RatingFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
            countries = filters.filterIsInstance<CountriesFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
            languages = filters.filterIsInstance<LanguagesFilter>().firstOrNull()?.toQueryPart() ?: emptyList(),
        )
    }

    // ============================== URL Builder Helpers ==============================
    // These make it trivial for the source's searchAnimeRequest to apply the params

    fun HttpUrl.Builder.addQueryParameterIfNotEmpty(query: String, value: String): HttpUrl.Builder {
        if (value.isNotEmpty()) addQueryParameter(query, value)
        return this
    }

    fun HttpUrl.Builder.addListQueryParameter(query: String, values: List<String>): HttpUrl.Builder {
        values.forEach { addQueryParameter("$query[]", it) }
        return this
    }

    // ============================== Data ==============================

    private object AnimeKaiThemeFiltersData {
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

        val YEARS = buildList {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            addAll(
                (currentYear + 1 downTo 2000).map {
                    Pair(it.toString(), it.toString())
                },
            )
            addAll(
                listOf(
                    "1990s",
                    "1980s",
                    "1970s",
                    "1960s",
                    "1950s",
                    "1940s",
                    "1930s",
                    "1920s",
                    "1910s",
                    "1900s",
                ).map { Pair(it, it) },
            )
        }

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
            Pair("Most viewed", "most_viewed"),
            Pair("Most followed", "most_followed"),
            Pair("Episode count", "episode_count"),
        )

        val STATUS = listOf(
            Pair("Not Yet Aired", "info"),
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
            Pair("Action", "47"), Pair("Adventure", "1"), Pair("Avant Garde", "235"),
            Pair("Boys Love", "184"), Pair("Comedy", "7"), Pair("Demons", "127"),
            Pair("Drama", "66"), Pair("Ecchi", "8"), Pair("Fantasy", "34"),
            Pair("Girls Love", "926"), Pair("Gourmet", "436"), Pair("Harem", "196"),
            Pair("Horror", "421"), Pair("Isekai", "77"), Pair("Iyashikei", "225"),
            Pair("Josei", "555"), Pair("Kids", "35"), Pair("Magic", "78"),
            Pair("Mahou Shoujo", "857"), Pair("Martial Arts", "92"), Pair("Mecha", "219"),
            Pair("Military", "134"), Pair("Music", "27"), Pair("Mystery", "48"),
            Pair("Parody", "356"), Pair("Psychological", "240"), Pair("Reverse Harem", "798"),
            Pair("Romance", "145"), Pair("School", "9"), Pair("Sci-Fi", "36"),
            Pair("Seinen", "189"), Pair("Shoujo", "183"), Pair("Shounen", "37"),
            Pair("Slice of Life", "125"), Pair("Space", "220"), Pair("Sports", "10"),
            Pair("Super Power", "350"), Pair("Supernatural", "49"), Pair("Suspense", "322"),
            Pair("Thriller", "241"), Pair("Vampire", "126"),
        )
    }
}
