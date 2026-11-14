package eu.kanade.tachiyomi.animeextension.all.moviebox

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object MovieBoxFilters {

    /** A content type of the site: [channelId] for browsing, [searchType] for keyword search. */
    class ContentType(val name: String, val channelId: Int?, val searchType: Int)

    val TYPES = listOf(
        ContentType("All", null, 0),
        ContentType("Movies", 1, 1),
        ContentType("TV Series", 2, 2),
        ContentType("Anime", 1006, 0),
        ContentType("Short TV", 7, 0),
        ContentType("Docs & Shows", 4, 0),
        ContentType("Education", 5, 5),
        ContentType("Music", 6, 6),
        ContentType("Gaming", 8, 0),
        ContentType("Sports", 9, 0),
        ContentType("Lifestyle", 10, 0),
    )

    class TypeFilter : AnimeFilter.Select<String>("Type", TYPES.map { it.name }.toTypedArray())

    open class ValueFilter(name: String, private val options: List<String>) : AnimeFilter.Select<String>(name, options.toTypedArray()) {
        val selected: String get() = options[state]
    }

    class GenreFilter : ValueFilter("Genre (Movies & TV)", GENRES)
    class CountryFilter : ValueFilter("Country", COUNTRIES)
    class YearFilter : ValueFilter("Year", YEARS)
    class LanguageFilter : ValueFilter("Language (Movies & TV)", LANGUAGES)

    class SortFilter : AnimeFilter.Select<String>("Sort by", SORTS.map { it.first }.toTypedArray()) {
        val selected: String get() = SORTS[state].second
    }

    fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("A search keyword only uses the Type filter"),
        TypeFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Browse filters (empty search)"),
        SortFilter(),
        GenreFilter(),
        CountryFilter(),
        YearFilter(),
        LanguageFilter(),
    )

    class Selection(
        val type: ContentType,
        val sort: String,
        val genre: String,
        val country: String,
        val year: String,
        val language: String,
    )

    fun parse(filters: AnimeFilterList): Selection {
        var type = TYPES.first()
        var sort = SORTS.first().second
        var genre = ALL
        var country = ALL
        var year = ALL
        var language = ALL
        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> type = TYPES[filter.state]
                is SortFilter -> sort = filter.selected
                is GenreFilter -> genre = filter.selected
                is CountryFilter -> country = filter.selected
                is YearFilter -> year = filter.selected
                is LanguageFilter -> language = filter.selected
                else -> {}
            }
        }
        return Selection(type, sort, genre, country, year, language)
    }

    const val ALL = "All"

    private val SORTS = listOf(
        "For you" to "ForYou",
        "Hottest" to "Hottest",
        "Latest" to "Latest",
        "Rating" to "Rating",
    )

    private val GENRES = listOf(
        ALL, "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama", "Family",
        "Fantasy", "Film-Noir", "Game-Show", "History", "Horror", "Music", "Musical", "Mystery", "News", "Reality-TV",
        "Romance", "Sci-Fi", "Short", "Sport", "Talk-Show", "Thriller", "War", "Western", "Other",
    )

    private val COUNTRIES = listOf(
        ALL, "United States", "United Kingdom", "Korea", "Japan", "Bangladesh", "China", "Egypt", "France", "Germany",
        "India", "Indonesia", "Iraq", "Italy", "Ivory Coast", "Kenya", "Lebanon", "Mexico", "Morocco", "Nigeria",
        "Pakistan", "Philippines", "Russia", "Saudi Arabia", "South Africa", "Spain", "Syria", "Thailand", "Malaysia",
        "Turkey", "Other",
    )

    private val YEARS = listOf(
        ALL, "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2010s", "2000s", "1990s", "1980s", "Other",
    )

    val LANGUAGES = listOf(
        ALL, "English dub", "French dub", "Hindi dub", "Bengali dub", "Urdu dub", "Punjabi dub", "Tamil dub",
        "Telugu dub", "Malayalam dub", "Kannada dub", "Arabic dub", "Arabic sub", "Tagalog dub", "Indonesian dub",
        "Russian dub", "Kurdish sub", "Spanish dub", "Spanish sub", "SpanishLatam dub",
    )
}
