package eu.kanade.tachiyomi.animeextension.all.torrentio

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object CatalogFilters {

    fun getFilterList(contentType: String = "all"): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        ServiceFilter(contentType),
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
    class ServiceFilter(
        contentType: String = "all",
    ) : AnimeFilter.Select<String>(
        "Platform",
        when (contentType) {
            "anime" -> {

                arrayOf("Crunchyroll")
            }
            "all" -> {
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
                )
            }
            else -> {
                arrayOf(
                    "Netflix",
                    "Disney+",
                    "Apple TV+",
                    "Amazon Prime",
                    "HBO Max",
                    "Paramount+",
                    "Hulu",
                    "Peacock",
                )
            }
        },
    ) {
        fun toCatalogId(): String {
            // Get the actual selected service name
            val selectedService = values.getOrNull(state) ?: return "nfx"

            return when (selectedService) {
                "Netflix" -> "nfx"
                "Disney+" -> "dpe"
                "Apple TV+" -> "atp"
                "Amazon Prime" -> "amp"
                "HBO Max" -> "hbm"
                "Paramount+" -> "pmp"
                "Hulu" -> "hlu"
                "Peacock" -> "pcp"
                "Crunchyroll" -> "cru"
                else -> "nfx"
            }
        }
    }
}
