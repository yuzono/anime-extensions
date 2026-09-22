package eu.kanade.tachiyomi.animeextension.en.anipm

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object Filters {

    // ============================== Selects ===============================
    class SortFilter : AnimeFilter.Select<String>("Sort", SORT_ENTRIES, 1) {
        fun getValue(): String = SORT_VALUES[state]
        companion object {
            private val SORT_ENTRIES = arrayOf("For you", "Trending", "Popular", "Top rated", "Newest", "A–Z")
            private val SORT_VALUES = arrayOf("recommended", "trending", "popular", "score", "newest", "title")
        }
    }

    class FormatFilter : AnimeFilter.Select<String>("Format", FORMAT_ENTRIES, 0) {
        fun getValue(): String? = FORMAT_VALUES[state]
        companion object {
            private val FORMAT_ENTRIES = arrayOf("Any", "TV", "Movie", "OVA", "ONA", "Special")
            private val FORMAT_VALUES = arrayOf(null, "TV", "MOVIE", "OVA", "ONA", "SPECIAL")
        }
    }

    class StatusFilter : AnimeFilter.Select<String>("Airing Status", STATUS_ENTRIES, 0) {
        fun getValue(): String? = STATUS_VALUES[state]
        companion object {
            private val STATUS_ENTRIES = arrayOf("Any", "Airing", "Finished", "Upcoming")
            private val STATUS_VALUES = arrayOf(null, "RELEASING", "FINISHED", "NOT_YET_RELEASED")
        }
    }

    class SeasonFilter : AnimeFilter.Select<String>("Season", SEASON_ENTRIES, 0) {
        fun getValue(): String? = SEASON_VALUES[state]
        companion object {
            private val SEASON_ENTRIES = arrayOf("Any", "Winter", "Spring", "Summer", "Fall")
            private val SEASON_VALUES = arrayOf(null, "WINTER", "SPRING", "SUMMER", "FALL")
        }
    }

    class YearFilter : AnimeFilter.Select<String>("Year", YEARS, 0) {
        fun getValue(): String? = if (state == 0) null else YEARS[state]
        companion object {
            private val YEARS: Array<String> = run {
                val nextYear = Calendar.getInstance().get(Calendar.YEAR) + 1
                (arrayOf("Any") + (nextYear downTo 1990).map(Int::toString))
            }
        }
    }

    // ========================= Checkbox groups =========================

    /** Group of TriState boxes: tap cycles ignore → include → exclude (site-native semantics). */
    abstract class TriStateValueGroup(name: String, values: List<String>) : AnimeFilter.Group<TriStateValueGroup.Box>(name, values.map { Box(it) }) {

        class Box(value: String) : TriState(value)

        /** Values the server should require (genre=, tag=, studio=). */
        fun getIncluded(): List<String> = state.filter { it.isIncluded() }.map { it.name }

        /** Values the server should reject (excludeGenre=, excludeTag=, excludeStudio=). */
        fun getExcluded(): List<String> = state.filter { it.isExcluded() }.map { it.name }
    }

    class GenreFilter(values: List<String>) : TriStateValueGroup("Genres", values)
    class TagFilter(values: List<String>) : TriStateValueGroup("Tags", values)
    class StudioFilter(values: List<String>) : TriStateValueGroup("Studios", values)

    fun build(facets: FacetsDto?): AnimeFilterList {
        val genres = facets?.genres.orEmpty().map { it.name }.sortedBy { it }
        val tags = facets?.tags.orEmpty().map { it.name }.sortedBy { it }
        val studios = facets?.studios.orEmpty().map { it.name }.sortedBy { it }

        val hint = AnimeFilter.Header("Facet lists load from the source — reopen filters in a moment")

        return AnimeFilterList(
            SortFilter(),
            if (genres.isEmpty()) hint else GenreFilter(genres),
            if (tags.isEmpty()) hint else TagFilter(tags),
            if (studios.isEmpty()) hint else StudioFilter(studios),
            YearFilter(),
            SeasonFilter(),
            FormatFilter(),
            StatusFilter(),
        )
    }
}
