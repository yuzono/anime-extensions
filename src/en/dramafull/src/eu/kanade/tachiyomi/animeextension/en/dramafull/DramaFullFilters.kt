package eu.kanade.tachiyomi.animeextension.en.dramafull

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object DramaFullFilters {

    open class SelectFilter(displayName: String, val vals: Array<Pair<String, Int>>, state: Int = 0) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
        fun getValue() = vals[state].second
    }

    class TypeFilter : SelectFilter("Type", TYPE_LIST)
    class CountryFilter : SelectFilter("Country", COUNTRY_LIST)
    class SortFilter : SelectFilter("Sort", SORT_LIST)

    // Default to "18+ included" (index 1 of the list)
    class AdultFilter(default: Int = 1) : SelectFilter("Adult filter", ADULT_FILTER.toList().toTypedArray(), default)

    class Genre(name: String, val id: Int) : AnimeFilter.CheckBox(name, false)
    class GenreFilter(genres: List<Genre> = GENRE_LIST) : AnimeFilter.Group<Genre>("Genres", genres)

    internal const val RATING_NON_ADULT = 0
    internal const val RATING_ADULT_INCLUDED = 1
    internal const val RATING_ADULT_ONLY = 2

    internal val ADULT_FILTER = listOf(
        "Non 18+" to RATING_NON_ADULT,
        "18+ included" to RATING_ADULT_INCLUDED,
        "18+ Only" to RATING_ADULT_ONLY,
    )

    private val TYPE_LIST = arrayOf(
        Pair("All", -1),
        Pair("TV-Show", 1),
        Pair("Movie", 2),
    )

    internal const val SORT_DEFAULT = 0
    internal const val SORT_RECENTLY_ADDED = 1
    internal const val SORT_RECENTLY_UPDATED = 2
    internal const val SORT_SCORE = 3
    internal const val SORT_NAME_AZ = 4
    internal const val SORT_MOST_WATCHED = 5

    private val SORT_LIST = arrayOf(
        Pair("<Default sort>", SORT_DEFAULT),
        Pair("Recently Added", SORT_RECENTLY_ADDED),
        Pair("Recently Updated", SORT_RECENTLY_UPDATED),
        Pair("Score", SORT_SCORE),
        Pair("Name A-Z", SORT_NAME_AZ),
        Pair("Most Watched", SORT_MOST_WATCHED),
    )

    private val COUNTRY_LIST = arrayOf(
        Pair("All", -1),
        Pair("Afghanistan", 135),
        Pair("American", 178),
        Pair("Argentina", 85),
        Pair("Australia", 75),
        Pair("Austria", 76),
        Pair("Bangladesh", 100),
        Pair("Belgium", 82),
        Pair("Bhutan", 105),
        Pair("Brunei", 128),
        Pair("Cambodia", 80),
        Pair("Canada", 68),
        Pair("Chile", 132),
        Pair("China", 52),
        Pair("Croatia", 131),
        Pair("Cuba", 124),
        Pair("Czech Republic", 86),
        Pair("Denmark", 81),
        Pair("Estonia", 118),
        Pair("Finland", 73),
        Pair("France", 65),
        Pair("Germany", 67),
        Pair("Hong Kong", 64),
        Pair("Iceland", 111),
        Pair("India", 55),
        Pair("Indian", 175),
        Pair("Indonesia", 72),
        Pair("Iran", 90),
        Pair("Ireland", 122),
        Pair("Italy", 78),
        Pair("Japan", 56),
        Pair("Kazakhstan", 110),
        Pair("Laos", 87),
        Pair("Latvia", 97),
        Pair("Lithuania", 127),
        Pair("Luxembourg", 104),
        Pair("Macao", 102),
        Pair("Malaysia", 69),
        Pair("Marathi", 153),
        Pair("Mexico", 106),
        Pair("Mongolia", 91),
        Pair("Morocco", 108),
        Pair("Myanmar", 115),
        Pair("Nepal", 93),
        Pair("Netherlands", 71),
        Pair("New Caledonia", 95),
        Pair("New Zealand", 94),
        Pair("North Korea", 79),
        Pair("Norway", 107),
        Pair("Other Asia", 173),
        Pair("Pakistan", 98),
        Pair("Palestinian Territory", 116),
        Pair("Philippines", 53),
        Pair("Poland", 109),
        Pair("Portugal", 120),
        Pair("Qatar", 101),
        Pair("Romania", 125),
        Pair("Russia", 112),
        Pair("Serbia", 129),
        Pair("Singapore", 66),
        Pair("South Africa", 84),
        Pair("South Korea", 6),
        Pair("Spain", 96),
        Pair("Sri Lanka", 119),
        Pair("Sweden", 89),
        Pair("Switzerland", 113),
        Pair("Taiwan", 54),
        Pair("Thailand", 57),
        Pair("Turkey", 123),
        Pair("United Arab Emirates", 126),
        Pair("United Kingdom", 74),
        Pair("United States", 77),
        Pair("Vietnam", 70),
    )

    private val GENRE_LIST = listOf(
        Genre("Action", 1),
        Genre("Adult", 2),
        Genre("Adventure", 3),
        Genre("Animation", 4),
        Genre("Award Winning", 272),
        Genre("Based on a Comic", 243),
        Genre("Based on True Story", 106),
        Genre("Business", 47),
        Genre("Childhood", 66),
        Genre("Comedy", 5),
        Genre("Crime", 8901),
        Genre("Documentary", 7),
        Genre("Drama", 8),
        Genre("Eastern", 9),
        Genre("Family", 10),
        Genre("Fantasy", 11),
        Genre("Game Show", 14),
        Genre("History", 15),
        Genre("Horror", 16),
        Genre("Indie", 216),
        Genre("LGBTQ+", 287),
        Genre("Manhua", 231),
        Genre("Mature", 67),
        Genre("Mini-Series", 18),
        Genre("Musical", 41),
        Genre("News", 21),
        Genre("One shot", 286),
        Genre("Other", 22),
        Genre("Power Struggle", 8899),
        Genre("Psychological", 68),
        Genre("Reality", 8904),
        Genre("Religion", 218),
        Genre("Romance", 24),
        Genre("Sci-fi", 107),
        Genre("Short", 26),
        Genre("Sitcom", 116),
        Genre("Society", 253),
        Genre("Soulmates", 242),
        Genre("Sport", 27),
        Genre("Thriller", 8902),
        Genre("Tragedy", 88),
        Genre("War", 8903),
        Genre("Yaoi", 232),
        Genre("Yuri", 250),
    )
}
