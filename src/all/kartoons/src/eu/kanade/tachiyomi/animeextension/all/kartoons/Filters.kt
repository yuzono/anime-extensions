package eu.kanade.tachiyomi.animeextension.all.kartoons

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {

    val CATALOG_OPTIONS = arrayOf(
        Pair("Popular Shows", "catalog/show?catalog=popular"),
        Pair("Latest Shows", "catalog/show?catalog=latest"),
        Pair("Trending Shows", "catalog/show?catalog=trending"),
        Pair("Popular Movies", "catalog/movie?catalog=popular"),
        Pair("Latest Movies", "catalog/movie?catalog=latest"),
        Pair("Trending Movies", "catalog/movie?catalog=trending"),
        Pair("⏱️ Continue Watching (Shows)", "stremio:catalog/series/kartoons_continue_shows"),
        Pair("⏱️ Continue Watching (Movies)", "stremio:catalog/movie/kartoons_continue_movies"),
        Pair("🌟 My Watchlist (Shows)", "stremio:catalog/series/kartoons_watchlist_shows"),
        Pair("🌟 My Watchlist (Movies)", "stremio:catalog/movie/kartoons_watchlist_movies"),
        Pair("All Shows", "catalog/show"),
        Pair("All Movies", "catalog/movie"),
    )

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
            CATALOG_OPTIONS,
        )

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Note: Search query ignores catalog filter"),
        CatalogFilter(),
    )
}
