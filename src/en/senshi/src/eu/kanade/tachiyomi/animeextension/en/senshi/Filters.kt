package eu.kanade.tachiyomi.animeextension.en.senshi

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object Filters {

    // =========================== Single-select ============================
    class SortFilter : AnimeFilter.Select<String>("Sort By", SORT_ENTRIES.toTypedArray(), 0) {
        fun getValue() = SORT_VALUES[state]
        companion object {
            private val SORT_ENTRIES = listOf("Popularity", "Highest Rated", "Lowest Rated", "A-Z Title", "Z-A Title", "Recently Added")
            private val SORT_VALUES = listOf("score_desc", "score_desc", "score_asc", "name_asc", "name_desc", "recent")
        }
    }

    class YearFilter : AnimeFilter.Select<String>("Year", YEAR_ENTRIES.toTypedArray(), 0) {
        fun getValue(): String? = if (state == 0) null else YEAR_ENTRIES[state]
        companion object {
            private val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            private val YEAR_ENTRIES = listOf("Any") + (currentYear downTo 2000).map(Int::toString)
        }
    }

    // =================== Multi-select (include/ignore) ====================
    class CheckBoxVal(name: String, val value: String) : AnimeFilter.CheckBox(name, false)

    class FormatFilter : AnimeFilter.Group<CheckBoxVal>("Format", FORMATS.map { CheckBoxVal(it.second, it.first) }) {
        fun getSelectedValues(): List<String> = state.filter { it.state }.map { it.value }
        companion object {
            private val FORMATS = listOf(
                "tv" to "TV",
                "movie" to "Movie",
                "ova" to "OVA",
                "ona" to "ONA",
                "special" to "Special",
            )
        }
    }

    class StatusFilter : AnimeFilter.Group<CheckBoxVal>("Status", STATUSES.map { CheckBoxVal(it.second, it.first) }) {
        fun getSelectedValues(): List<String> = state.filter { it.state }.map { it.value }
        companion object {
            private val STATUSES = listOf(
                "not_yet_aired" to "Not Yet Aired",
                "releasing" to "Releasing",
                "completed" to "Completed",
            )
        }
    }

    class LanguageFilter : AnimeFilter.Group<CheckBoxVal>("Language", LANGUAGES.map { CheckBoxVal(it.second, it.first) }) {
        fun getSelectedValues(): List<String> = state.filter { it.state }.map { it.value }
        companion object {
            private val LANGUAGES = listOf(
                "HardSub" to "Sub",
                "Dub" to "Dub",
            )
        }
    }

    class SeasonFilter : AnimeFilter.Group<CheckBoxVal>("Season", SEASONS.map { CheckBoxVal(it.second, it.first) }) {
        fun getSelectedValues(): List<String> = state.filter { it.state }.map { it.value }
        companion object {
            private val SEASONS = listOf(
                "winter" to "Winter",
                "spring" to "Spring",
                "summer" to "Summer",
                "fall" to "Fall",
            )
        }
    }

    // ====================== Genres (include/exclude) ======================
    class GenreTriState(name: String) : AnimeFilter.TriState(name)

    class GenreFilter : AnimeFilter.Group<GenreTriState>("Genres", GENRES.map { GenreTriState(it) }) {
        fun getIncluded(): List<String> = state.filter { it.isIncluded() }.map { it.name }

        fun getExcluded(): List<String> = state.filter { it.isExcluded() }.map { it.name }
        companion object {
            private val GENRES = listOf(
                "Action", "Adventure", "Avant Garde", "Award Winning", "Boys Love", "Comedy",
                "Demons", "Drama", "Ecchi", "Fantasy", "Girls Love", "Gourmet", "Harem",
                "Horror", "Isekai", "Iyashikei", "Josei", "Kids", "Magic", "Mahou Shoujo",
                "Martial Arts", "Mecha", "Military", "Music", "Mystery", "Parody",
                "Psychological", "Racing", "Reverse Harem", "Romance", "Samurai", "School",
                "Sci-Fi", "Seinen", "Shoujo", "Shounen", "Slice of Life", "Space", "Sports",
                "Super Power", "Supernatural", "Suspense", "Thriller", "Vampire",
            )
        }
    }

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            SortFilter(),
            FormatFilter(),
            GenreFilter(),
            StatusFilter(),
            SeasonFilter(),
            LanguageFilter(),
            YearFilter(),
        )
}
