package eu.kanade.tachiyomi.animeextension.en.animepahe

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import java.util.Calendar

object Filters {

    class GenresFilter :
        UriPartFilter(
            "Browse by Genre",
            arrayOf(
                Pair("<select>", ""),
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
                Pair("Mystery", "mystery"),
                Pair("Romance", "romance"),
                Pair("Sci-Fi", "sci-fi"),
                Pair("Slice of Life", "slice-of-life"),
                Pair("Sports", "sports"),
                Pair("Supernatural", "supernatural"),
                Pair("Suspense", "suspense"),
            ),
        )

    class ThemeFilter :
        UriPartFilter(
            "Browse by Theme",
            arrayOf(
                Pair("<select>", ""),
                Pair("Adult Cast", "adult-cast"),
                Pair("Anthropomorphic", "anthropomorphic"),
                Pair("CGDCT", "cgdct"),
                Pair("Childcare", "childcare"),
                Pair("Combat Sports", "combat-sports"),
                Pair("Crossdressing", "crossdressing"),
                Pair("Delinquents", "delinquents"),
                Pair("Detective", "detective"),
                Pair("Educational", "educational"),
                Pair("Gag Humor", "gag-humor"),
                Pair("Gore", "gore"),
                // Pair("Gourmet", "gourmet"),
                Pair("Harem", "harem"),
                Pair("High Stakes Game", "high-stakes-game"),
                Pair("Historical", "historical"),
                Pair("Idols (Female)", "idols-female"),
                Pair("Idols (Male)", "idols-male"),
                Pair("Isekai", "isekai"),
                Pair("Iyashikei", "iyashikei"),
                Pair("Love Polygon", "love-polygon"),
                Pair("Love Status Quo", "love-status-quo"),
                Pair("Magical Sex Shift", "magical-sex-shift"),
                Pair("Mahou Shoujo", "mahou-shoujo"),
                Pair("Martial Arts", "martial-arts"),
                Pair("Mecha", "mecha"),
                Pair("Medical", "medical"),
                Pair("Military", "military"),
                Pair("Music", "music"),
                Pair("Mythology", "mythology"),
                Pair("Organized Crime", "organized-crime"),
                Pair("Otaku Culture", "otaku-culture"),
                Pair("Parody", "parody"),
                Pair("Performing Arts", "performing-arts"),
                Pair("Pets", "pets"),
                Pair("Psychological", "psychological"),
                Pair("Racing", "racing"),
                Pair("Reincarnation", "reincarnation"),
                Pair("Reverse Harem", "reverse-harem"),
                Pair("Romantic Subtext", "romantic-subtext"),
                Pair("Samurai", "samurai"),
                Pair("School", "school"),
                Pair("Showbiz", "showbiz"),
                Pair("Space", "space"),
                Pair("Strategy Game", "strategy-game"),
                Pair("Super Power", "super-power"),
                Pair("Survival", "survival"),
                Pair("Team Sports", "team-sports"),
                Pair("Time Travel", "time-travel"),
                Pair("Urban Fantasy", "urban-fantasy"),
                Pair("Vampire", "vampire"),
                Pair("Video Game", "video-game"),
                Pair("Villainess", "villainess"),
                Pair("Visual Arts", "visual-arts"),
                Pair("Workplace", "workplace"),
            ),
        )

    class DemographicFilter :
        UriPartFilter(
            "Browse by Demographic",
            arrayOf(
                Pair("<select>", ""),
                Pair("Shounen", "shounen"),
                Pair("Shoujo", "shoujo"),
                Pair("Seinen", "seinen"),
                Pair("Josei", "josei"),
                Pair("Kids", "kids"),
            ),
        )

    class YearFilter :
        UriPartFilter(
            "Browse by Season",
            YEARS,
        ) {
        companion object {
            private val CURRENT_YEAR by lazy {
                Calendar.getInstance().get(Calendar.YEAR)
            }

            private val YEARS = buildList {
                add(Pair("<select>", ""))
                addAll(
                    (CURRENT_YEAR downTo 1968).map {
                        Pair(it.toString(), it.toString())
                    },
                )
            }.toTypedArray()
        }
    }

    class SeasonFilter :
        UriPartFilter(
            "Season of Year",
            arrayOf(
                Pair("Spring", "spring"),
                Pair("Summer", "summer"),
                Pair("Fall", "fall"),
                Pair("Winter", "winter"),
            ),
        )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }
}
