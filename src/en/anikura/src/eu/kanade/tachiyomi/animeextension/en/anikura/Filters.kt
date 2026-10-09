package eu.kanade.tachiyomi.animeextension.en.anikura

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {

    val FILTER_LIST get() = AnimeFilterList(
        SortFilter(),
        StatusFilter(),
    )

    class SortFilter :
        AnimeFilter.Select<String>(
            "Sort by",
            arrayOf("Popular", "Top rated", "Newest"),
        ) {
        val selected get() = state
    }

    class StatusFilter :
        AnimeFilter.Select<String>(
            "Status",
            arrayOf("Any", "Releasing", "Finished", "Not yet aired"),
        ) {
        val value: String?
            get() = when (state) {
                1 -> "releasing"
                2 -> "finished"
                3 -> "not_yet_aired"
                else -> null
            }
    }
}
