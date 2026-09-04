package eu.kanade.tachiyomi.animeextension.en.animetoki

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeTokiFilters {

    open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                Pair("Any", ""),
                Pair("Action", "action"),
                Pair("Adventure", "adventure"),
                Pair("Comedy", "comedy"),
                Pair("Drama", "drama"),
                Pair("Ecchi", "ecchi"),
                Pair("Fantasy", "fantasy"),
                Pair("Harem", "harem"),
                Pair("Horror", "horror"),
                Pair("Isekai", "isekai"),
                Pair("Military", "military"),
                Pair("Mystery", "mystery"),
                Pair("Psychological", "psychological"),
                Pair("Romance", "romance"),
                Pair("School", "school"),
                Pair("Sci-fi", "sci-fi"),
                Pair("Shoujo", "shoujo"),
                Pair("Shounen", "shounen"),
                Pair("Slice of Life", "slice-of-life"),
                Pair("Sports", "sports"),
                Pair("Supernatural", "supernatural-2"),
            ),
        )

    class SubPageFilter :
        UriPartFilter(
            "Sub-page",
            arrayOf(
                Pair("Any", ""),
                Pair("Anime Series", "anime-series"),
                Pair("Ongoing Anime", "ongoing-anime"),
                Pair("Anime Movies", "anime-movies"),
            ),
        )

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SubPageFilter(),
        GenreFilter(),
    )
}
