package eu.kanade.tachiyomi.animeextension.en.anv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.serialization.Serializable
import java.util.Calendar

@Serializable
class CatalogFiltersData(
    val genres: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
)

object AnvFilters {

    private val SORT_OPTIONS = arrayOf(
        "Most Popular" to "POPULARITY_DESC",
        "Trending Now" to "TRENDING_DESC",
        "Highest Score" to "SCORE_DESC",
        "Newest Releases" to "START_DATE_DESC",
        "Title (A-Z)" to "TITLE_ROMAJI",
    )

    private val FORMAT_OPTIONS = arrayOf(
        "Any" to "",
        "TV" to "TV",
        "Movie" to "MOVIE",
        "OVA" to "OVA",
        "ONA" to "ONA",
        "Special" to "SPECIAL",
        "TV Short" to "TV_SHORT",
        "Music" to "MUSIC",
    )

    private val STATUS_OPTIONS = arrayOf(
        "Any" to "",
        "Releasing" to "RELEASING",
        "Finished" to "FINISHED",
        "Not Yet Released" to "NOT_YET_RELEASED",
        "Hiatus" to "HIATUS",
        "Cancelled" to "CANCELLED",
    )

    private val SEASON_OPTIONS = arrayOf(
        "Any" to "",
        "Winter" to "WINTER",
        "Spring" to "SPRING",
        "Summer" to "SUMMER",
        "Fall" to "FALL",
    )

    private val DEFAULT_GENRES = listOf(
        "Action",
        "Adventure",
        "Cars",
        "Comedy",
        "Dementia",
        "Demons",
        "Drama",
        "Ecchi",
        "Fantasy",
        "Game",
        "Harem",
        "Historical",
        "Horror",
        "Josei",
        "Kids",
        "Magic",
        "Martial Arts",
        "Mecha",
        "Military",
        "Music",
        "Mystery",
        "Parody",
        "Police",
        "Psychological",
        "Romance",
        "Samurai",
        "School",
        "Sci-Fi",
        "Seinen",
        "Shoujo",
        "Shounen",
        "Slice of Life",
        "Space",
        "Sports",
        "Super Power",
        "Supernatural",
        "Thriller",
        "Vampire",
    )

    @Volatile
    private var cachedGenres: List<String> = DEFAULT_GENRES

    @Volatile
    private var cachedYears: List<String> = buildDefaultYears()

    private fun buildDefaultYears(): List<String> {
        val maxYear = Calendar.getInstance().get(Calendar.YEAR) + 1
        return (maxYear downTo 1960).map { it.toString() }
    }

    fun updateDynamicFilters(genres: List<String>, years: List<Int>) {
        val cleanGenres = genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanGenres.isNotEmpty()) {
            cachedGenres = cleanGenres
        }
        val cleanYears = years.filter { it in 1940..2100 }.sortedDescending().map { it.toString() }
        if (cleanYears.isNotEmpty()) {
            cachedYears = cleanYears
        }
    }

    fun buildFilterList(): AnimeFilterList {
        val yearPairs = buildList {
            add("Any" to "")
            cachedYears.forEach { add(it to it) }
        }.toTypedArray()

        val genreCheckboxes = cachedGenres.map { GenreCheckBox(it) }

        return AnimeFilterList(
            SortFilter(),
            FormatFilter(),
            StatusFilter(),
            SeasonFilter(),
            YearFilter(yearPairs),
            GenreFilter(genreCheckboxes),
        )
    }

    data class FilterParams(
        val sort: String = "POPULARITY_DESC",
        val format: String = "",
        val status: String = "",
        val season: String = "",
        val seasonYear: Int? = null,
        val genres: List<String> = emptyList(),
    ) {
        val hasActiveFilters: Boolean
            get() = sort != "POPULARITY_DESC" ||
                format.isNotEmpty() ||
                status.isNotEmpty() ||
                season.isNotEmpty() ||
                seasonYear != null ||
                genres.isNotEmpty()
    }

    fun parseFilters(filters: AnimeFilterList): FilterParams {
        var sort = "POPULARITY_DESC"
        var format = ""
        var status = ""
        var season = ""
        var seasonYear: Int? = null
        val genres = mutableListOf<String>()

        for (filter in filters) {
            when (filter) {
                is SortFilter -> sort = filter.selectedValue
                is FormatFilter -> format = filter.selectedValue
                is StatusFilter -> status = filter.selectedValue
                is SeasonFilter -> season = filter.selectedValue
                is YearFilter -> seasonYear = filter.selectedValue.toIntOrNull()
                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach { genres.add(it.name) }
                }
                else -> {}
            }
        }

        return FilterParams(
            sort = sort,
            format = format,
            status = status,
            season = season,
            seasonYear = seasonYear,
            genres = genres,
        )
    }

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        defaultIndex: Int = 0,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
        defaultIndex,
    ) {
        val selectedValue: String
            get() = vals.getOrNull(state)?.second.orEmpty()
    }

    class SortFilter : UriPartFilter("Sort By", SORT_OPTIONS)
    class FormatFilter : UriPartFilter("Format", FORMAT_OPTIONS)
    class StatusFilter : UriPartFilter("Airing Status", STATUS_OPTIONS)
    class SeasonFilter : UriPartFilter("Season", SEASON_OPTIONS)
    class YearFilter(options: Array<Pair<String, String>>) : UriPartFilter("Year", options)

    class GenreCheckBox(name: String) : AnimeFilter.CheckBox(name, false)
    class GenreFilter(genres: List<GenreCheckBox>) : AnimeFilter.Group<GenreCheckBox>("Genres & Demographics", genres)
}
