package eu.kanade.tachiyomi.animeextension.id.kuramanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object KuramanimeFilters {
    open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun toNamePart() = vals[state].first
    }

    class OrderByFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("A-Z", "ascending"),
                Pair("Z-A", "descending"),
                Pair("Oldest", "oldest"),
                Pair("Latest Added", "latest"),
                Pair("Popular", "popular"),
                Pair("Most Viewed", "most_viewed"),
                Pair("Latest Update", "updated"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Currently Airing", "ongoing"),
                Pair("Finished Airing", "finished"),
                Pair("Upcoming", "upcoming"),
            ),
        )

    class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("All", ""),
                Pair("TV", "tv"),
                Pair("Movie", "movie"),
                Pair("OVA", "ova"),
                Pair("ONA", "ona"),
                Pair("Special", "special"),
                Pair("TV Special", "tv-special"),
                Pair("PV", "pv"),
                Pair("CM", "cm"),
            ),
        )

    class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                Pair("All", ""),
                Pair("Action", "action"),
                Pair("Adventure", "adventure"),
                Pair("Avant Garde", "avant-garde"),
                Pair("Award Winning", "award-winning"),
                Pair("Boys Love", "boys-love"),
                Pair("Comedy", "comedy"),
                Pair("Drama", "drama"),
                Pair("Ecchi", "ecchi"),
                Pair("Erotica", "erotica"),
                Pair("Fantasy", "fantasy"),
                Pair("Girls Love", "girls-love"),
                Pair("Gourmet", "gourmet"),
                Pair("Hentai", "hentai"),
                Pair("Horror", "horror"),
                Pair("Music", "music"),
                Pair("Mystery", "mystery"),
                Pair("Romance", "romance"),
                Pair("Sci-Fi", "sci-fi"),
                Pair("Slice of Life", "slice-of-life"),
                Pair("Sports", "sports"),
                Pair("Supernatural", "supernatural"),
                Pair("Suspense", "suspense"),
            ),
        )

    val FILTER_LIST
        get() = AnimeFilterList(
            OrderByFilter(),
            StatusFilter(),
            TypeFilter(),
            GenreFilter(),
        )
}
