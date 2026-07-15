package eu.kanade.tachiyomi.animeextension.en.anidb

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import java.util.Calendar

object Filters {

    class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("All", ""),
                Pair("Movie", "Movie"),
                Pair("Music", "Music"),
                Pair("ONA", "ONA"),
                Pair("OVA", "OVA"),
                Pair("Special", "Special"),
                Pair("TV", "TV"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Currently Airing", "Currently Airing"),
                Pair("Finished Airing", "Finished Airing"),
            ),
        )

    class SeasonFilter :
        UriPartFilter(
            "Season",
            arrayOf(
                Pair("All", ""),
                Pair("Spring", "spring"),
                Pair("Summer", "summer"),
                Pair("Fall", "fall"),
                Pair("Winter", "winter"),
            ),
        )

    class YearFilter : UriPartFilter("Year", YEARS) {
        companion object {
            private val CURRENT_YEAR by lazy {
                Calendar.getInstance().get(Calendar.YEAR)
            }

            private val YEARS = buildList {
                add(Pair("All", ""))
                addAll((CURRENT_YEAR downTo 1968).map { Pair(it.toString(), it.toString()) })
                add(Pair("1925", "1925"))
            }.toTypedArray()
        }
    }

    class DemographicFilter :
        UriPartFilter(
            "Demographic",
            arrayOf(
                Pair("All", ""),
                Pair("Shounen", "1"),
                Pair("Seinen", "2"),
                Pair("Shoujo", "5"),
                Pair("Kids", "4"),
                Pair("Josei", "3"),
            ),
        )

    class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                Pair("All", ""),
                Pair("Action", "1"),
                Pair("Adventure", "3"),
                Pair("Avant Garde", "19"),
                Pair("Award Winning", "12"),
                Pair("Boys Love", "16"),
                Pair("Comedy", "5"),
                Pair("Drama", "2"),
                Pair("Ecchi", "13"),
                Pair("Erotica", "17"),
                Pair("Fantasy", "4"),
                Pair("Girls Love", "20"),
                Pair("Gourmet", "8"),
                Pair("Hentai", "15"),
                Pair("Horror", "21"),
                Pair("Mystery", "7"),
                Pair("Romance", "14"),
                Pair("Sci-Fi", "6"),
                Pair("Slice of Life", "9"),
                Pair("Sports", "11"),
                Pair("Supernatural", "10"),
                Pair("Suspense", "18"),
            ),
        )

    class ThemeFilter :
        UriPartFilter(
            "Theme",
            arrayOf(
                Pair("All", ""),
                Pair("Adult Cast", "13"),
                Pair("Anthropomorphic", "34"),
                Pair("CGDCT", "35"),
                Pair("Childcare", "31"),
                Pair("Combat Sports", "5"),
                Pair("Comedy", "56"),
                Pair("Crossdressing", "36"),
                Pair("Delinquents", "46"),
                Pair("Detective", "40"),
                Pair("Educational", "53"),
                Pair("Gag Humor", "38"),
                Pair("Gore", "42"),
                Pair("Harem", "12"),
                Pair("High Stakes Game", "52"),
                Pair("Historical", "24"),
                Pair("Idols (Female)", "18"),
                Pair("Idols (Male)", "50"),
                Pair("Isekai", "9"),
                Pair("Iyashikei", "47"),
                Pair("Love Polygon", "33"),
                Pair("Love Status Quo", "45"),
                Pair("Magical Sex Shift", "51"),
                Pair("Mahou Shoujo", "41"),
                Pair("Martial Arts", "39"),
                Pair("Mecha", "23"),
                Pair("Medical", "48"),
                Pair("Military", "10"),
                Pair("Music", "19"),
                Pair("Mythology", "4"),
                Pair("Organized Crime", "25"),
                Pair("Otaku Culture", "14"),
                Pair("Parody", "3"),
                Pair("Performing Arts", "32"),
                Pair("Pets", "29"),
                Pair("Psychological", "20"),
                Pair("Racing", "49"),
                Pair("Reincarnation", "7"),
                Pair("Reverse Harem", "11"),
                Pair("Romantic Subtext", "37"),
                Pair("Samurai", "26"),
                Pair("School", "6"),
                Pair("Showbiz", "8"),
                Pair("Space", "44"),
                Pair("Strategy Game", "43"),
                Pair("Super Power", "1"),
                Pair("Supernatural", "54"),
                Pair("Survival", "21"),
                Pair("Suspense", "55"),
                Pair("Team Sports", "17"),
                Pair("Time Travel", "15"),
                Pair("Urban Fantasy", "27"),
                Pair("Vampire", "28"),
                Pair("Video Game", "2"),
                Pair("Villainess", "22"),
                Pair("Visual Arts", "30"),
                Pair("Workplace", "16"),
            ),
        )

    class SortFilter :
        UriPartFilter(
            "Sort by",
            arrayOf(
                Pair("Trending", "order_trending"),
                Pair("Top Rated", "order_top"),
                Pair("Latest Updated", "order_updated"),
                Pair("Most Popular", "order_popular"),
                Pair("Most Favorited", "order_favorite"),
                Pair("Top Airing", "order_top_airing"),
                Pair("Title A-Z", "title"),
                Pair("Newest First", "aired_start"),
            ),
        )

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }
}
