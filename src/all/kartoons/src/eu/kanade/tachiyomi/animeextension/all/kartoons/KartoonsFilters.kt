package eu.kanade.tachiyomi.animeextension.all.kartoons

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object KartoonsFilters {

    open class SelectFilter(
        displayName: String,
        val options: Array<Pair<String, String>>,
        defaultIndex: Int = 0,
    ) : AnimeFilter.Select<String>(
        displayName,
        options.map { it.first }.toTypedArray(),
        defaultIndex,
    ) {
        val selected: String
            get() = options[state].second
    }

    class CatalogFilter :
        SelectFilter(
            "Catalog",
            arrayOf(
                Pair("Popular Shows", "catalog/show?catalog=popular"),
                Pair("Popular Movies", "catalog/movie?catalog=popular"),
                Pair("All Shows", "catalog/show"),
                Pair("All Movies", "catalog/movie"),
            ),
        )

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Note: Search query ignores catalog filter"),
        CatalogFilter(),
    )
}
