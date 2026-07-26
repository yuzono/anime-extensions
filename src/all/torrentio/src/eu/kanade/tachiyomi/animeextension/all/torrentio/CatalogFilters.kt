package eu.kanade.tachiyomi.animeextension.all.torrentio

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object CatalogFilters {

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        ServiceFilter(),
    )

    fun mediaType(filters: AnimeFilterList): List<String> {
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val selected = typeFilter?.values?.getOrNull(typeFilter.state) ?: "All"
        return when (selected) {
            "Movie" -> listOf("movie")
            "Series" -> listOf("series")
            else -> listOf("movie", "series")
        }
    }

    fun streamingService(filters: AnimeFilterList): String {
        val networkFilter = filters.filterIsInstance<ServiceFilter>().firstOrNull()
        return networkFilter?.toCatalogId() ?: "nfx"
    }

    class TypeFilter : AnimeFilter.Select<String>("Type", arrayOf("All", "Movie", "Series"))

    class ServiceFilter :
        AnimeFilter.Select<String>(
            "Platform",
            arrayOf(
                "Netflix",
                "Disney+",
                "Apple TV+",
                "Amazon Prime",
                "HBO Max",
                "Paramount+",
                "Hulu",
                "Peacock",
                "Crunchyroll",
            ),
        ) {
        fun toCatalogId(): String = when (state) {
            0 -> "nfx" // Netflix
            1 -> "dpe" // Disney+
            2 -> "atp" // Apple TV+
            3 -> "amp" // Amazon Prime
            4 -> "hbm" // HBO Max
            5 -> "pmp" // Paramount+
            6 -> "hlu" // Hulu
            7 -> "pcp" // Peacock
            8 -> "cru" // Crunchyroll
            else -> "nfx" // Fallback default Netflix
        }
    }
}
