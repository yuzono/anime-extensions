package eu.kanade.tachiyomi.animeextension.en.anv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

/**
 * Builds and parses static catalog search filters for the Anv source.
 */
object Filters {

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

    private val GENRES = listOf(
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
        "Mahou Shoujo",
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

    /**
     * Generates descending release year options from next year down to 1940.
     */
    private fun buildYearOptions(): Array<Pair<String, String>> {
        val maxYear = Calendar.getInstance().get(Calendar.YEAR) + 1
        return buildList {
            add("Any" to "")
            for (year in maxYear downTo 1940) {
                val label = year.toString()
                add(label to label)
            }
        }.toTypedArray()
    }

    /**
     * Constructs a fresh [AnimeFilterList] with all supported sort, format, status, season, year, and genre filters.
     */
    fun buildFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        FormatFilter(),
        StatusFilter(),
        SeasonFilter(),
        YearFilter(buildYearOptions()),
        GenreFilter(GENRES.map { GenreCheckBox(it) }),
    )

    /**
     * Parsed filter selections ready to pass to `BrowseAnime`.
     */
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

    /**
     * Extracts active [FilterParams] from the user-selected [filters].
     */
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

    /**
     * Base select filter mapping display labels to API parameter values.
     */
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

    /** Sort order dropdown filter. */
    class SortFilter : UriPartFilter("Sort By", SORT_OPTIONS)

    /** Media format dropdown filter. */
    class FormatFilter : UriPartFilter("Format", FORMAT_OPTIONS)

    /** Airing status dropdown filter. */
    class StatusFilter : UriPartFilter("Airing Status", STATUS_OPTIONS)

    /** Release season dropdown filter. */
    class SeasonFilter : UriPartFilter("Season", SEASON_OPTIONS)

    /** Release year dropdown filter. */
    class YearFilter(options: Array<Pair<String, String>>) : UriPartFilter("Year", options)

    /** Individual genre checkbox item. */
    class GenreCheckBox(name: String) : AnimeFilter.CheckBox(name, false)

    /** Multi-select genre checkbox group. */
    class GenreFilter(genres: List<GenreCheckBox>) : AnimeFilter.Group<GenreCheckBox>("Genres & Demographics", genres)
}
