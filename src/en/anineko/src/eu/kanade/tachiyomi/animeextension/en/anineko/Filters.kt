package eu.kanade.tachiyomi.animeextension.en.anineko

import eu.kanade.tachiyomi.animeextension.en.anineko.AniNeko.CheckBoxFilterList
import eu.kanade.tachiyomi.animeextension.en.anineko.AniNeko.UriPartFilter

object Filters {

    class GenreFilter : CheckBoxFilterList("Genres", GENRES)
    class TypeFilter : CheckBoxFilterList("Types", TYPES)
    class StatusFilter : CheckBoxFilterList("Status", STATUSES)
    class LanguageFilter : CheckBoxFilterList("Languages", LANGUAGES)
    class YearFilter : CheckBoxFilterList("Years", YEARS)
    class SortFilter : UriPartFilter("Sort By", SORT_BY)

    private val GENRES = arrayOf(
        Pair("Action", "action"),
        Pair("Adventure", "adventure"),
        Pair("Cars", "cars"),
        Pair("Comedy", "comedy"),
        Pair("Dementia", "dementia"),
        Pair("Demons", "demons"),
        Pair("Drama", "drama"),
        Pair("Ecchi", "ecchi"),
        Pair("Fantasy", "fantasy"),
        Pair("Game", "game"),
        Pair("Harem", "harem"),
        Pair("Historical", "historical"),
        Pair("Horror", "horror"),
        Pair("Isekai", "isekai"),
        Pair("Josei", "josei"),
        Pair("Kids", "kids"),
        Pair("Magic", "magic"),
        Pair("Mahou Shoujo", "mahou-shoujo"),
        Pair("Martial Arts", "martial-arts"),
        Pair("Mecha", "mecha"),
        Pair("Military", "military"),
        Pair("Music", "music"),
        Pair("Mystery", "mystery"),
        Pair("Parody", "parody"),
        Pair("Police", "police"),
        Pair("Psychological", "psychological"),
        Pair("Romance", "romance"),
        Pair("Samurai", "samurai"),
        Pair("School", "school"),
        Pair("Sci-Fi", "sci-fi"),
        Pair("Seinen", "seinen"),
        Pair("Shoujo", "shoujo"),
        Pair("Shoujo Ai", "shoujo-ai"),
        Pair("Shounen", "shounen"),
        Pair("Shounen Ai", "shounen-ai"),
        Pair("Slice of Life", "slice-of-life"),
        Pair("Space", "space"),
        Pair("Sports", "sports"),
        Pair("Super Power", "super-power"),
        Pair("Supernatural", "supernatural"),
        Pair("Thriller", "thriller"),
        Pair("Vampire", "vampire"),
    )

    private val TYPES = arrayOf(
        Pair("TV", "1"),
        Pair("Movie", "2"),
        Pair("OVA", "3"),
        Pair("ONA", "4"),
        Pair("Special", "5"),
        Pair("Music", "6"),
        Pair("TV_SHORT", "7"),
    )

    private val STATUSES = arrayOf(
        Pair("Ongoing", "Ongoing"),
        Pair("Completed", "Completed"),
        Pair("Upcoming", "info"),
    )

    private val LANGUAGES = arrayOf(
        Pair("Subbed", "sub"),
        Pair("Dubbed", "dub"),
    )

    private val YEARS = (2026 downTo 1917).map { Pair(it.toString(), it.toString()) }.toTypedArray()

    private val SORT_BY = arrayOf(
        Pair("Latest Update", "recently_updated"),
        Pair("Release Date", "release_date"),
        Pair("Recently Added", "recently_added"),
        Pair("Title A-Z", "title_az"),
    )
}
