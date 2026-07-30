package eu.kanade.tachiyomi.animeextension.en.reanime

import android.os.Build
import androidx.annotation.RequiresApi
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object ReAnimeFilters {

    class SortFilter : AnimeFilter.Select<String>("Sort By", SORT_ENTRIES.toTypedArray(), 0) {
        fun getValue() = SORT_VALUES[state]
        companion object {
            private val SORT_ENTRIES = listOf("Popularity", "Score", "Year")
            private val SORT_VALUES = listOf("popularity_desc", "score_desc", "year_desc")
        }
    }

    class FormatFilter : AnimeFilter.Select<String>("Format", FORMAT_ENTRIES.toTypedArray(), 0) {
        fun getValue() = if (state == 0) null else FORMAT_VALUES[state]
        companion object {
            private val FORMAT_ENTRIES = listOf("Any", "TV", "Movie", "Special", "OVA", "ONA", "Music")
            private val FORMAT_VALUES = listOf("", "TV", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC")
        }
    }

    class StatusFilter : AnimeFilter.Select<String>("Airing Status", STATUS_ENTRIES.toTypedArray(), 0) {
        fun getValue() = if (state == 0) null else STATUS_VALUES[state]
        companion object {
            private val STATUS_ENTRIES = listOf("Any", "Finished", "Releasing", "Not Yet Released", "Cancelled")
            private val STATUS_VALUES = listOf("", "Finished", "Releasing", "Not Yet Released", "Cancelled")
        }
    }

    class SeasonFilter : AnimeFilter.Select<String>("Season", SEASON_ENTRIES.toTypedArray(), 0) {
        fun getValue() = if (state == 0) null else SEASON_VALUES[state]
        companion object {
            private val SEASON_ENTRIES = listOf("Any", "Winter", "Spring", "Summer", "Fall")
            private val SEASON_VALUES = listOf("", "WINTER", "SPRING", "SUMMER", "FALL")
        }
    }

    class OriginFilter : AnimeFilter.Select<String>("Origin", ORIGIN_ENTRIES.toTypedArray(), 0) {
        fun getValue() = if (state == 0) null else ORIGIN_VALUES[state]
        companion object {
            private val ORIGIN_ENTRIES = listOf("Any", "Japan", "South Korea", "China", "Taiwan")
            private val ORIGIN_VALUES = listOf("", "JP", "KR", "CN", "TW")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    class YearFilter : AnimeFilter.Select<String>("Year", YEAR_ENTRIES.toTypedArray(), 0) {
        fun getValue() = if (state == 0) null else YEAR_VALUES[state]
        companion object {
            @RequiresApi(Build.VERSION_CODES.O)
            private val currentYear = java.time.LocalDate.now().year

            @RequiresApi(Build.VERSION_CODES.O)
            private val YEAR_ENTRIES = listOf("Any") + (currentYear downTo 1977).map { it.toString() }

            @RequiresApi(Build.VERSION_CODES.O)
            private val YEAR_VALUES = listOf("") + (currentYear downTo 1977).map { it.toString() }
        }
    }

    class GenreCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name, false)
    class GenreFilter : AnimeFilter.Group<GenreCheckBox>("Genres", GENRES.map { GenreCheckBox(it, it) }) {
        fun getSelectedValues(): String = state.filter { it.state }.joinToString(",") { it.value }
        companion object {
            private val GENRES = listOf(
                "Action", "Action & Adventure", "Adventure", "Animation", "Avant Garde",
                "Award Winning", "Boys Love", "Comedy", "Drama", "Ecchi", "Erotica",
                "Fantasy", "Girls Love", "Gourmet", "Hentai", "Horror", "Mahou Shoujo",
                "Mecha", "Music", "Mystery", "Psychological", "Romance", "Sci-Fi",
                "Sci-Fi & Fantasy", "Science Fiction", "Slice of Life", "Sports",
                "Supernatural", "Suspense", "Thriller",
            ).sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
    }

    class CharacterCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name, false)
    class CharacterFilter : AnimeFilter.Group<CharacterCheckBox>("Characters", CHARACTERS.map { CharacterCheckBox(it, it) }) {
        fun getSelectedValues(): String = state.filter { it.state }.joinToString(",") { it.value }
        companion object {
            private val CHARACTERS = listOf(
                "Arsène Lupin III", "Conan Edogawa", "Daisuke Jigen", "Doraemon",
                "Fujiko Mine", "Goemon Ishikawa XIII", "Kouichi Zenigata", "Maria",
                "Miku Hatsune", "Musashi", "Nami", "Narrator", "Nobita Nobi",
                "Nyarth", "Pikachu", "Ran Mouri", "Sakura", "Satoshi", "Sensei",
                "Suneo Honekawa",
            ).sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
    }

    class StaffCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name, false)
    class StaffFilter : AnimeFilter.Group<StaffCheckBox>("Staff", STAFF_LIST.map { StaffCheckBox(it, it) }) {
        fun getSelectedValues(): String = state.filter { it.state }.joinToString(",") { it.value }
        companion object {
            private val STAFF_LIST = listOf(
                "Aki Hata", "Atsuhiro Iwakami", "Gen Fukunaga", "Hajime Yatate",
                "Hironori Tanaka", "Jin Aketagawa", "John Ledford", "Justin Cook",
                "Masafumi Mima", "Masao Maruyama", "Michiko Yokote", "Miku Hatsune",
                "Reiko Yoshida", "Satoshi Motoyama", "Toshiki Kameyama", "Yasumasa Koyama",
                "Yoshihiko Umakoshi", "Yoshikazu Iwanami", "Yoshiyuki Tomino", "Youta Tsuruoka",
            ).sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
    }

    class StudioCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name, false)
    class StudioFilter : AnimeFilter.Group<StudioCheckBox>("Studios", STUDIOS.map { StudioCheckBox(it, it) }) {
        fun getSelectedValues(): String = state.filter { it.state }.joinToString(",") { it.value }
        companion object {
            private val STUDIOS = listOf(
                "NHK", "Toei Animation", "Aniplex", "Funimation", "Sunrise",
                "Sentai Filmworks", "TV Tokyo", "Production I.G", "Movic",
                "J.C.STAFF", "KADOKAWA", "Tencent Penguin Pictures", "MADHOUSE",
                "Pony Canyon", "TMS Entertainment", "AT-X", "bilibili",
                "Lantis", "Kodansha", "Studio DEEN",
            ).sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
    }

    class TagCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name, false)
    class TagFilter : AnimeFilter.Group<TagCheckBox>("Tags", TAGS.map { TagCheckBox(it, it) }) {
        fun getSelectedValues(): String = state.filter { it.state }.joinToString(",") { it.value }
        companion object {
            private val TAGS = listOf(
                "4-koma", "Achromatic", "Achronological Order", "Acrobatics", "Acting", "Adoption",
                "Advertisement", "Afterlife", "Age Gap", "Age Regression", "Agender", "Agriculture",
                "Ahegao", "Airsoft", "Alchemy", "Aliens", "Alternate Universe", "American Football",
                "Amnesia", "Amputation", "Anachronism", "Anal Sex", "Ancient China", "Angels",
                "Animals", "Anthology", "Anthropomorphism", "Anti-Hero", "Archery", "Armpits",
                "Aromantic", "Arranged Marriage", "Artificial Intelligence", "Asexual", "Ashikoki",
                "Asphyxiation", "Assassins", "Astronomy", "Athletics", "Augmented Reality",
                "Autobiographical", "Aviation", "Badminton", "Ballet", "Band", "Bar", "Baseball",
                "Basketball", "Battle Royale", "Biographical", "Bisexual", "Blackmail", "Board Game",
                "Boarding School", "Body Horror", "Body Image", "Body Swapping", "Bondage", "Boobjob",
                "Bowling", "Boxing", "Boys' Love", "Brainwashing", "Bullying", "Butler",
                "Calligraphy", "Camping", "Cannibalism", "Card Battle", "Cars", "Centaur",
                "Cervix Penetration", "CGI", "Cheating", "Cheerleading", "Chibi", "Chimera",
                "Chuunibyou", "Circus", "Class Struggle", "Classic Literature", "Classical Music",
                "Clone", "Coastal", "Cohabitation", "College", "Coming of Age", "Conspiracy",
                "Cosmic Horror", "Cosplay", "Cowboys", "Creature Taming", "Crime",
                "Criminal Organization", "Crossdressing", "Crossover", "Cult", "Cultivation",
                "Cumflation", "Cunnilingus", "Curses", "Cute Boys Doing Cute Things",
                "Cute Girls Doing Cute Things", "Cyberpunk", "Cyborg", "Cycling", "Dancing",
                "Death Game", "Deepthroat", "Defloration", "Delinquents", "Demons", "Denpa",
                "Desert", "Detective", "DILF", "Dinosaurs", "Disability", "Dissociative Identities",
                "Double Penetration", "Dragons", "Drawing", "Drugs", "Dullahan", "Dungeon",
                "Dystopian", "E-Sports", "Eco-Horror", "Economics", "Educational",
                "Elderly Protagonist", "Elf", "Ensemble Cast", "Environmental", "Episodic",
                "Ero Guro", "Espionage", "Estranged Family", "Exhibitionism", "Exorcism",
                "Facial", "Fairy", "Fairy Tale", "Fake Relationship", "Family Life", "Fashion",
                "Feet", "Fellatio", "Female Harem", "Female Protagonist", "Femboy", "Femdom",
                "Fencing", "Filmmaking", "Fingering", "Firefighters", "Fishing", "Fisting",
                "Fitness", "Flash", "Flat Chest", "Food", "Football", "Foreign", "Found Family",
                "Fugitive", "Full CGI", "Futanari", "Gambling", "Gangs", "Gender Bending",
                "Ghost", "Go", "Goblin", "Gods", "Golf", "Gore", "Graduation Project", "Group Sex",
                "Guns", "Gyaru", "Hair Pulling", "Handball", "Handjob", "Henshin", "Heterosexual",
                "Hikikomori", "Hip-hop Music", "Historical", "Homeless", "Horticulture",
                "Human Experimentation", "Human Pet", "Hypersexuality", "Ice Skating", "Idol",
                "Incest", "Indigenous Cultures", "Inn", "Inseki", "Interspecies", "Irrumatio",
                "Isekai", "Iyashikei", "Jazz Music", "Josei", "Judo", "Kabuki", "Kaiju", "Karuta",
                "Kemonomimi", "Kids", "Kingdom Management", "Konbini", "Kuudere", "Lacrosse",
                "Lactation", "Language Barrier", "Large Breasts", "LGBTQ+ Themes", "Long Strip",
                "Lost Civilization", "Love Triangle", "Mafia", "Magic", "Mahjong", "Maids",
                "Makeup", "Male Harem", "Male Pregnancy", "Male Protagonist", "Manzai", "Marriage",
                "Martial Arts", "Masochism", "Masturbation", "Matchmaking", "Mating Press",
                "Matriarchy", "Medicine", "Medieval", "Memory Manipulation", "Mermaid", "Meta",
                "Metal Music", "MILF", "Military", "Mixed Gender Harem", "Mixed Media", "Modeling",
                "Monster Boy", "Monster Girl", "Mopeds", "Motorcycles", "Mountaineering",
                "Musical Theater", "Mythology", "Nakadashi", "Natural Disaster", "Necromancy",
                "Nekomimi", "Netorare", "Netorase", "Netori", "Ninja", "No Dialogue", "Noir",
                "Non-fiction", "Nudity", "Nun", "Office", "Office Lady", "Oiran", "Ojou-sama",
                "Omegaverse", "Orphan", "Otaku Culture", "Outdoor Activities", "Oyakodon",
                "Pandemic", "Parenthood", "Parkour", "Parody", "Pet Play", "Philosophy",
                "Photography", "Pirates", "Poker", "Police", "Politics", "Polyamorous",
                "Post-Apocalyptic", "POV", "Pregnancy", "Primarily Adult Cast",
                "Primarily Animal Cast", "Primarily Child Cast", "Primarily Female Cast",
                "Primarily Male Cast", "Primarily Teen Cast", "Prison", "Prophecy", "Prostitution",
                "Proxy Battle", "Psychosexual", "Public Sex", "Puppetry", "Rakugo", "Rape",
                "Real Robot", "Rehabilitation", "Reincarnation", "Religion", "Rescue", "Restaurant",
                "Revenge", "Reverse Isekai", "Rimjob", "Robots", "Rock Music", "Rotoscoping",
                "Royal Affairs", "Rugby", "Rural", "Sadism", "Samurai", "Satire", "Scat", "School",
                "School Club", "Scissoring", "Scuba Diving", "Seinen", "Sex Toys", "Shapeshifting",
                "Shimaidon", "Ships", "Shogi", "Shoujo", "Shounen", "Shrine Maiden",
                "Skateboarding", "Skeleton", "Slapstick", "Slavery", "Snowscape",
                "Software Development", "Space", "Space Opera", "Spearplay", "Squirting",
                "Steampunk", "Stop Motion", "Succubus", "Suicide", "Sumata", "Sumo", "Super Power",
                "Super Robot", "Superhero", "Surfing", "Surreal Comedy", "Survival", "Sweat",
                "Swimming", "Swordplay", "Table Tennis", "Tanks", "Tanned Skin", "Teacher",
                "Teens' Love", "Tennis", "Tentacles", "Terrorism", "Threesome", "Time Loop",
                "Time Manipulation", "Time Skip", "Tokusatsu", "Tomboy", "Torture", "Tragedy",
                "Trains", "Transgender", "Travel", "Triads", "Tsundere", "Twins", "Unrequited Love",
                "Urban", "Urban Fantasy", "Vampire", "Vertical Video", "Veterinarian",
                "Video Games", "Vikings", "Villainess", "Virginity", "Virtual World", "Vocal Synth",
                "Volleyball", "Vore", "Voyeur", "VTuber", "War", "Watersports", "Werewolf",
                "Wilderness", "Witch", "Work", "Wrestling", "Writing", "Wuxia", "Yakuza",
                "Yandere", "Youkai", "Yuri", "Zombie", "Zoophilia",
            )
        }
    }

    val FILTER_LIST
        @RequiresApi(Build.VERSION_CODES.O)
        get() = AnimeFilterList(
            SortFilter(),
            FormatFilter(),
            StatusFilter(),
            SeasonFilter(),
            OriginFilter(),
            YearFilter(),
            GenreFilter(),
            CharacterFilter(),
            StaffFilter(),
            StudioFilter(),
            TagFilter(),
        )
}
