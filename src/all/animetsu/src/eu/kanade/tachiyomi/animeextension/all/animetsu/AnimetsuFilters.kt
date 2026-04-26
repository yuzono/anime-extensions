package eu.kanade.tachiyomi.animeextension.all.animetsu

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimetsuFilters {

    class GenreCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name, false)
    class GenreFilter : AnimeFilter.Group<GenreCheckBox>("Genres", GENRES.map { GenreCheckBox(it.first, it.second) }) {
        fun getSelectedValues(): String = state.filter { it.state }.joinToString(",") { it.value }
        companion object {
            private val GENRES = listOf(
                "Action" to "Action",
                "Adventure" to "Adventure",
                "Comedy" to "Comedy",
                "Drama" to "Drama",
                "Ecchi" to "Ecchi",
                "Fantasy" to "Fantasy",
                "Horror" to "Horror",
                "Mahou Shoujo" to "Mahou Shoujo",
                "Mecha" to "Mecha",
                "Music" to "Music",
                "Mystery" to "Mystery",
                "Psychological" to "Psychological",
                "Romance" to "Romance",
                "Sci-Fi" to "Sci-Fi",
                "Slice of Life" to "Slice of Life",
                "Sports" to "Sports",
                "Supernatural" to "Supernatural",
                "Thriller" to "Thriller",
            )
        }
    }

    class FormatFilter : AnimeFilter.Select<String>("Format", FORMAT_ENTRIES.toTypedArray(), 0) {
        fun getValue() = if (state == 0) null else FORMAT_VALUES[state]
        companion object {
            private val FORMAT_ENTRIES = listOf(
                "Any",
                "Movie",
                "TV",
                "TV Short",
                "Special",
                "OVA",
                "ONA",
            )
            private val FORMAT_VALUES = listOf(
                "",
                "MOVIE",
                "TV",
                "TV_SHORT",
                "SPECIAL",
                "OVA",
                "ONA",
            )
        }
    }

    class YearFilter : AnimeFilter.Select<String>("Year", YEAR_ENTRIES.toTypedArray(), 0) {
        fun getValue() = if (state == 0) null else YEAR_VALUES[state]
        companion object {
            private val YEAR_ENTRIES = listOf("Any") + (2026 downTo 1970).map { it.toString() }
            private val YEAR_VALUES = listOf("") + (2026 downTo 1970).map { it.toString() }
        }
    }

    class SortFilter : AnimeFilter.Select<String>("Sort By", SORT_ENTRIES.toTypedArray(), 0) {
        fun getValue() = SORT_VALUES[state]
        companion object {
            private val SORT_ENTRIES = listOf(
                "Popularity",
                "Average Score",
                "Release Date",
                "Favourites",
                "Trending",
            )
            private val SORT_VALUES = listOf(
                "popularity",
                "average_score",
                "date_desc",
                "favourites",
                "trending",
            )
        }
    }

    class SeasonFilter : AnimeFilter.Select<String>("Season", SEASON_ENTRIES.toTypedArray(), 0) {
        fun getValue() = if (state == 0) null else SEASON_VALUES[state]
        companion object {
            private val SEASON_ENTRIES = listOf("Any", "Winter", "Spring", "Summer", "Fall")
            private val SEASON_VALUES = listOf("", "WINTER", "SPRING", "SUMMER", "FALL")
        }
    }

    class StatusFilter : AnimeFilter.Select<String>("Airing Status", STATUS_ENTRIES.toTypedArray(), 0) {
        fun getValue() = if (state == 0) null else STATUS_VALUES[state]
        companion object {
            private val STATUS_ENTRIES = listOf("Any", "Ongoing", "Finished", "Upcoming", "Cancelled")
            private val STATUS_VALUES = listOf("", "RELEASING", "FINISHED", "NOT_YET_RELEASED", "CANCELLED")
        }
    }

    class TagCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name, false)
    class TagFilter : AnimeFilter.Group<TagCheckBox>("Tags", TAGS.map { TagCheckBox(it, it) }) {
        fun getSelectedValues(): String = state.filter { it.state }.joinToString(",") { it.value }
        companion object {
            private val TAGS = listOf(
                "4-koma",
                "Achronological Order",
                "Afterlife",
                "Age Gap",
                "Airsoft",
                "Aliens",
                "Alternate Universe",
                "American Football",
                "Amnesia",
                "Anti-Hero",
                "Archery",
                "Assassins",
                "Athletics",
                "Augmented Reality",
                "Aviation",
                "Badminton",
                "Band",
                "Bar",
                "Baseball",
                "Basketball",
                "Battle Royale",
                "Biographical",
                "Bisexual",
                "Body Swapping",
                "Boxing",
                "Bullying",
                "Calligraphy",
                "Card Battle",
                "Cars",
                "CGI",
                "Chibi",
                "Chuunibyou",
                "Classic Literature",
                "College",
                "Coming of Age",
                "Cosplay",
                "Crossdressing",
                "Crossover",
                "Cultivation",
                "Cute Girls Doing Cute Things",
                "Cyberpunk",
                "Cycling",
                "Dancing",
                "Delinquents",
                "Demons",
                "Development",
                "Dragons",
                "Drawing",
                "Dystopian",
                "Economics",
                "Educational",
                "Ensemble Cast",
                "Environmental",
                "Episodic",
                "Espionage",
                "Fairy Tale",
                "Family Life",
                "Fashion",
                "Female Protagonist",
                "Fishing",
                "Fitness",
                "Flash",
                "Food",
                "Football",
                "Foreign",
                "Fugitive",
                "Full CGI",
                "Full Colour",
                "Gambling",
                "Gangs",
                "Gender Bending",
                "Gender Neutral",
                "Ghost",
                "Gods",
                "Gore",
                "Guns",
                "Gyaru",
                "Harem",
                "Henshin",
                "Hikikomori",
                "Ice Skating",
                "Historical",
                "Idol",
                "Isekai",
                "Iyashikei",
                "Josei",
                "Kaiju",
                "Karuta",
                "Kemonomimi",
                "Kids",
                "Love Triangle",
                "Mafia",
                "Magic",
                "Mahjong",
                "Male Protagonist",
                "Maids",
                "Martial Arts",
                "Memory Manipulation",
                "Meta",
                "Monster Girl",
                "Military",
                "Mopeds",
                "Motorcycles",
                "Musical",
                "Mythology",
                "Nekomimi",
                "Ninja",
                "No Dialogue",
                "Noir",
                "Nudity",
                "Otaku Culture",
                "Outdoor",
                "Parody",
                "Philosophy",
                "Pirates",
                "Photography",
                "Poker",
                "Police",
                "Politics",
                "Post-Apocalyptic",
                "Primarily Adult Cast",
                "Primarily Female Cast",
                "Primarily Male Cast",
                "Puppetry",
                "Real Robot",
                "Reincarnation",
                "Rehabilitation",
                "Revenge",
                "Reverse Harem",
                "Rugby",
                "Robots",
                "Rural",
                "Samurai",
                "Satire",
                "School",
                "School Club",
                "Seinen",
                "Ships",
                "Shogi",
                "Shoujo",
                "Shoujo Ai",
                "Shounen",
                "Shounen Ai",
                "Slapstick",
                "Slavery",
                "Space",
                "Space Opera",
                "Steampunk",
                "Stop Motion",
                "Super Power",
                "Super Robot",
                "Superhero",
                "Surreal Comedy",
                "Survival",
                "Swimming",
                "Swordplay",
                "Table Tennis",
                "Tanks",
                "Teacher",
                "Terrorism",
                "Tennis",
                "Time Manipulation",
                "Time Skip",
                "Tragedy",
                "Trains",
                "Triads",
                "Tsundere",
                "Urban Fantasy",
                "Vampire",
                "Video Games",
                "Virtual World",
                "Volleyball",
                "War",
                "Witch",
                "Work",
                "Wrestling",
                "Writing",
                "Wuxia",
                "Yakuza",
                "Yandere",
                "Youkai",
                "Zombie",
            )
        }
    }

    class CountryFilter : AnimeFilter.Select<String>("Country of Origin", COUNTRY_ENTRIES.toTypedArray(), 0) {
        fun getValue() = if (state == 0) null else COUNTRY_VALUES[state]
        companion object {
            private val COUNTRY_ENTRIES = listOf("Any", "Japan", "South Korea", "China", "Taiwan")
            private val COUNTRY_VALUES = listOf("", "JP", "KR", "CN", "TW")
        }
    }

    class SourceFilter : AnimeFilter.Select<String>("Source Material", SOURCE_ENTRIES.toTypedArray(), 0) {
        fun getValue() = if (state == 0) null else SOURCE_VALUES[state]
        companion object {
            private val SOURCE_ENTRIES = listOf(
                "Any",
                "Original",
                "Anime",
                "Manga",
                "Novel",
                "Light Novel",
                "Web Novel",
                "Comic",
                "Doujinshi",
                "Live Action",
                "Video Game",
                "Game",
                "Multimedia Project",
                "Picture Book",
                "Other",
            )
            private val SOURCE_VALUES = listOf(
                "",
                "ORIGINAL",
                "ANIME",
                "MANGA",
                "NOVEL",
                "LIGHT_NOVEL",
                "WEB_NOVEL",
                "COMIC",
                "DOUJINSHI",
                "LIVE_ACTION",
                "VIDEO_GAME",
                "GAME",
                "MULTIMEDIA_PROJECT",
                "PICTURE_BOOK",
                "OTHER",
            )
        }
    }

    val FILTER_LIST get() = AnimeFilterList(
        GenreFilter(),
        FormatFilter(),
        YearFilter(),
        SortFilter(),
        SeasonFilter(),
        StatusFilter(),
        TagFilter(),
        CountryFilter(),
        SourceFilter(),
    )
}
