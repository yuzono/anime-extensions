package eu.kanade.tachiyomi.animeextension.en.anikuro

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import java.util.Calendar

object Filters {

    open class SelectFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        defaultState: Int = 0,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
        defaultState,
    ) {
        fun selectedValue() = vals[state].second
        fun isDefault() = state == 0
    }

    class SortFilter :
        SelectFilter(
            "Sort By",
            arrayOf(
                Pair("Best Match", "POPULARITY_DESC"),
                Pair("Start Date ▲", "START_DATE"),
                Pair("Start Date ▼", "START_DATE_DESC"),
                Pair("End Date ▲", "END_DATE"),
                Pair("End Date ▼", "END_DATE_DESC"),
                Pair("Popularity ▲", "POPULARITY"),
                Pair("Popularity ▼", "POPULARITY_DESC"),
                Pair("Trending ▲", "TRENDING"),
                Pair("Trending ▼", "TRENDING_DESC"),
                Pair("Episodes ▲", "EPISODES"),
                Pair("Episodes ▼", "EPISODES_DESC"),
            ),
        )

    class FormatFilter :
        SelectFilter(
            "Format",
            arrayOf(
                Pair("All", ""),
                Pair("TV", "TV"),
                Pair("Movie", "MOVIE"),
                Pair("ONA", "ONA"),
                Pair("OVA", "OVA"),
                Pair("Special", "SPECIAL"),
                Pair("Music", "MUSIC"),
            ),
        )

    class StatusFilter :
        SelectFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Releasing", "RELEASING"),
                Pair("Finished", "FINISHED"),
                Pair("Not Yet Released", "NOT_YET_RELEASED"),
                Pair("Cancelled", "CANCELLED"),
                Pair("Hiatus", "HIATUS"),
            ),
        )

    class SeasonFilter :
        SelectFilter(
            "Season",
            arrayOf(
                Pair("All", ""),
                Pair("Winter", "WINTER"),
                Pair("Spring", "SPRING"),
                Pair("Summer", "SUMMER"),
                Pair("Fall", "FALL"),
            ),
        )

    class OriginFilter :
        SelectFilter(
            "Origin",
            arrayOf(
                Pair("All", ""),
                Pair("Japan", "JP"),
                Pair("China", "CN"),
                Pair("Korea", "KR"),
            ),
        )

    class MinScoreFilter :
        SelectFilter(
            "Min. Score",
            arrayOf(
                Pair("Any", ""),
                Pair("50+", "50"),
                Pair("60+", "60"),
                Pair("70+", "70"),
                Pair("75+", "75"),
                Pair("80+", "80"),
                Pair("85+", "85"),
                Pair("90+", "90"),
            ),
        )

    class YearFilter :
        SelectFilter(
            "Year",
            YEAR_ENTRIES,
        ) {
        companion object {
            private val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            private val YEAR_ENTRIES = arrayOf(Pair("Any", "")) +
                (currentYear downTo 1990).map { Pair(it.toString(), it.toString()) }.toTypedArray()
        }
    }

    class GenreTriState(name: String, val id: String = name) : AnimeFilter.TriState(name)

    class GenreFilter :
        AnimeFilter.Group<GenreTriState>(
            "Genres",
            GENRES.sortedBy { it }.map { GenreTriState(it) },
        ) {
        fun getIncluded(): List<String> = state.filter { it.isIncluded() }.map { it.id }
        fun getExcluded(): List<String> = state.filter { it.isExcluded() }.map { it.id }
    }

    class TagTriState(name: String, val id: String = name) : AnimeFilter.TriState(name)

    class TagFilter :
        AnimeFilter.Group<TagTriState>(
            "Tags",
            TAGS.sortedBy { it }.map { TagTriState(it) },
        ) {
        fun getIncluded(): List<String> = state.filter { it.isIncluded() }.map { it.id }
        fun getExcluded(): List<String> = state.filter { it.isExcluded() }.map { it.id }
    }

    private val GENRES = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Fantasy",
        "Horror", "Mystery", "Romance", "Sci-Fi", "Slice of Life",
        "Sports", "Supernatural", "Thriller", "Mecha", "Music",
    )

    private val TAGS = listOf(
        "Isekai", "Magic", "School", "Shounen", "Seinen",
        "Shoujo", "Josei", "Love Triangle", "Parody", "Super Power",
        "Martial Arts", "Military", "Historical", "Demons", "Vampire",
        "Samurai", "Space", "Time Manipulation", "Video Games", "Survival",
        "Satire", "Mythology", "Mafia", "Post-Apocalyptic", "Cyberpunk",
        "Zombie", "Aliens", "Ninja", "Pirates", "Detective",
        "Yuri", "LGBTQ+ Themes",
    )
}
