package eu.kanade.tachiyomi.animeextension.en.anikage

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    class SortFilter :
        UriPartFilter(
            "Sort by",
            arrayOf(
                Pair("Popularity", "popularity"),
                Pair("Score", "score"),
                Pair("Trending", "trending"),
                Pair("Favourites", "favourites"),
                Pair("Newest", "year"),
                Pair("Recently updated", "updated"),
                Pair("Title", "title"),
            ),
        )

    class FormatFilter :
        UriPartFilter(
            "Format",
            arrayOf(
                Pair("Any", ""),
                Pair("TV", "TV"),
                Pair("TV Short", "TV_SHORT"),
                Pair("Movie", "MOVIE"),
                Pair("Special", "SPECIAL"),
                Pair("OVA", "OVA"),
                Pair("ONA", "ONA"),
                Pair("Music", "MUSIC"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("Any", ""),
                Pair("Releasing", "RELEASING"),
                Pair("Finished", "FINISHED"),
                Pair("Not yet released", "NOT_YET_RELEASED"),
                Pair("Cancelled", "CANCELLED"),
                Pair("Hiatus", "HIATUS"),
            ),
        )

    class SeasonFilter :
        UriPartFilter(
            "Season",
            arrayOf(
                Pair("Any", ""),
                Pair("Winter", "WINTER"),
                Pair("Spring", "SPRING"),
                Pair("Summer", "SUMMER"),
                Pair("Fall", "FALL"),
            ),
        )

    class YearFilter : AnimeFilter.Text("Year")

    class GenreCheckBox(name: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter : AnimeFilter.Group<GenreCheckBox>("Genres", GENRES.map { GenreCheckBox(it) }) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { it.name }
    }

    fun build(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters can be combined with a text search"),
        SortFilter(),
        FormatFilter(),
        StatusFilter(),
        SeasonFilter(),
        YearFilter(),
        GenreFilter(),
    )

    private val GENRES = listOf(
        "Action",
        "Adventure",
        "Comedy",
        "Drama",
        "Ecchi",
        "Fantasy",
        "Horror",
        "Mahou Shoujo",
        "Mecha",
        "Music",
        "Mystery",
        "Psychological",
        "Romance",
        "Sci-Fi",
        "Slice of Life",
        "Sports",
        "Supernatural",
        "Thriller",
    )
}
