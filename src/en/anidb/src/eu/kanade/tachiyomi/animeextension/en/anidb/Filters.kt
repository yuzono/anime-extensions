package eu.kanade.tachiyomi.animeextension.en.anidb

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {

    class SectionFilter : UriPartFilter("Section", SECTIONS) {
        companion object {
            val SECTIONS = arrayOf(
                Pair("None", ""),
                Pair("Spotlight", "Spotlight"),
                Pair("Trending", "Trending"),
                Pair("Most Popular", "Most Popular"),
                Pair("Most Watched", "Most Watched"),
                Pair("Top Airing", "Top Airing"),
                Pair("Most Favorite", "Most Favorite"),
                Pair("Top 10 Today", "Top 10 Today"),
                Pair("Top 10 Week", "Top 10 Week"),
                Pair("Top 10 Month", "Top 10 Month"),
            )
        }
    }

    class GenreFilter : UriPartFilter("Genre", GENRES) {
        companion object {
            val GENRES = arrayOf(
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
            )
        }
    }

    class ThemeFilter : UriPartFilter("Theme", THEMES) {
        companion object {
            val THEMES = arrayOf(
                Pair("All", ""),
                Pair("Adult Cast", "130000"),
                Pair("Anthropomorphic", "340000"),
                Pair("CGDCT", "350000"),
                Pair("Childcare", "310000"),
                Pair("Combat Sports", "50000"),
                Pair("Comedy", "560000"),
                Pair("Crossdressing", "360000"),
                Pair("Delinquents", "460000"),
                Pair("Detective", "400000"),
                Pair("Educational", "530000"),
                Pair("Gag Humor", "380000"),
                Pair("Gore", "420000"),
                Pair("Harem", "120000"),
                Pair("High Stakes Game", "520000"),
                Pair("Historical", "240000"),
                Pair("Idols (Female)", "180000"),
                Pair("Idols (Male)", "500000"),
                Pair("Isekai", "90000"),
                Pair("Iyashikei", "470000"),
                Pair("Love Polygon", "330000"),
                Pair("Love Status Quo", "450000"),
                Pair("Magical Sex Shift", "510000"),
                Pair("Mahou Shoujo", "410000"),
                Pair("Martial Arts", "390000"),
                Pair("Mecha", "230000"),
                Pair("Medical", "480000"),
                Pair("Military", "100000"),
                Pair("Music", "190000"),
                Pair("Mythology", "40000"),
                Pair("Organized Crime", "250000"),
                Pair("Otaku Culture", "140000"),
                Pair("Parody", "30000"),
                Pair("Performing Arts", "320000"),
                Pair("Pets", "290000"),
                Pair("Psychological", "200000"),
                Pair("Racing", "490000"),
                Pair("Reincarnation", "70000"),
                Pair("Reverse Harem", "110000"),
                Pair("Romantic Subtext", "370000"),
                Pair("Samurai", "260000"),
                Pair("School", "60000"),
                Pair("Showbiz", "80000"),
                Pair("Space", "440000"),
                Pair("Strategy Game", "430000"),
                Pair("Super Power", "10000"),
                Pair("Supernatural", "540000"),
                Pair("Survival", "210000"),
                Pair("Suspense", "550000"),
                Pair("Team Sports", "170000"),
                Pair("Time Travel", "150000"),
                Pair("Urban Fantasy", "270000"),
                Pair("Vampire", "280000"),
                Pair("Video Game", "20000"),
                Pair("Villainess", "220000"),
                Pair("Visual Arts", "300000"),
                Pair("Workplace", "160000"),
            )
        }
    }

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }
}
