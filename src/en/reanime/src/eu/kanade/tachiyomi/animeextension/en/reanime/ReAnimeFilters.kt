package eu.kanade.tachiyomi.animeextension.en.reanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object ReAnimeFilters {

    class SortFilter : AnimeFilter.Select<String>("Sort By", SORT_ENTRIES.toTypedArray(), 0) {
        fun getValue() = SORT_VALUES[state]
        companion object {
            private val SORT_ENTRIES = listOf("Popularity", "Trending", "Average Score", "Release Date")
            private val SORT_VALUES = listOf("popularity_desc", "trending_desc", "score_desc", "date_desc")
        }
    }

    class FormatFilter : AnimeFilter.Select<String>("Format", FORMAT_ENTRIES.toTypedArray(), 0) {
        fun getValue() = if (state == 0) null else FORMAT_VALUES[state]
        companion object {
            private val FORMAT_ENTRIES = listOf("Any", "TV", "TV Short", "Movie", "Special", "OVA", "ONA", "Music", "PV", "CM", "TV Special")
            private val FORMAT_VALUES = listOf("", "TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC", "PV", "CM", "TV Special")
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

    class YearFilter : AnimeFilter.Select<String>("Year", YEAR_ENTRIES.toTypedArray(), 0) {
        fun getValue() = if (state == 0) null else YEAR_VALUES[state]
        companion object {
            private val YEAR_ENTRIES = listOf("Any") + (2026 downTo 1977).map { it.toString() }
            private val YEAR_VALUES = listOf("") + (2026 downTo 1977).map { it.toString() }
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
                "Sci-Fi & Fantasy", "Slice of Life", "Sports", "Supernatural", "Suspense", "Thriller",
            ).sorted()
        }
    }

    class CharacterCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name, false)
    class CharacterFilter : AnimeFilter.Group<CharacterCheckBox>("Characters", CHARACTERS.map { CharacterCheckBox(it, it) }) {
        fun getSelectedValues(): String = state.filter { it.state }.joinToString(",") { it.value }
        companion object {
            private val CHARACTERS = listOf(
                "Arsène Lupin III", "Conan Edogawa", "Daisuke Jigen", "Doraemon", "Fujiko Mine",
                "Goemon Ishikawa XIII", "Kouichi Zenigata", "Maria", "Miku Hatsune", "Musashi",
                "Nami", "Narrator", "Nobita Nobi", "Nyarth", "Pikachu", "Ran Mouri", "Sakura",
                "Satoshi", "Sensei", "Suneo Honekawa",
            ).sorted()
        }
    }

    class StaffCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name, false)
    class StaffFilter : AnimeFilter.Group<StaffCheckBox>("Staff", STAFF_LIST.map { StaffCheckBox(it, it) }) {
        fun getSelectedValues(): String = state.filter { it.state }.joinToString(",") { it.value }
        companion object {
            private val STAFF_LIST = listOf(
                "Aki Hata", "Atsuhiro Iwakami", "Gen Fukunaga", "Hajime Yatate", "Hironori Tanaka",
                "Jin Aketagawa", "John Ledford", "Justin Cook", "Masafumi Mima", "Masao Maruyama",
                "Michiko Yokote", "Miku Hatsune", "Reiko Yoshida", "Satoshi Motoyama", "Toshiki Kameyama",
                "Yasumasa Koyama", "Yoshihiko Umakoshi", "Yoshikazu Iwanami", "Yoshiyuki Tomino", "Youta Tsuruoka",
            ).sorted()
        }
    }

    class StudioCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name, false)
    class StudioFilter : AnimeFilter.Group<StudioCheckBox>("Studios", STUDIOS.map { StudioCheckBox(it, it) }) {
        fun getSelectedValues(): String = state.filter { it.state }.joinToString(",") { it.value }
        companion object {
            private val STUDIOS = listOf(
                "Aniplex", "AT-X", "bilibili", "Funimation", "J.C.STAFF", "KADOKAWA", "Kodansha",
                "Lantis", "MADHOUSE", "Movic", "NHK", "Pony Canyon", "Production I.G", "Sunrise",
                "Studio DEEN", "Sentai Filmworks", "Tencent Penguin Pictures", "TMS Entertainment",
                "Toei Animation", "TV Tokyo",
            ).sorted()
        }
    }

    class TagCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name, false)
    class TagFilter : AnimeFilter.Group<TagCheckBox>("Tags", TAGS.map { TagCheckBox(it, it) }) {
        fun getSelectedValues(): String = state.filter { it.state }.joinToString(",") { it.value }
        companion object {
            private val TAGS = listOf(
                "4-koma", "Achromatic", "Achronological Order", "Acrobatics", "Acting",
                "Adoption", "Advertisement", "Afterlife", "Age Gap", "Age Regression",
                "Agender", "Agriculture", "Ahegao", "Airsoft", "Alchemy", "Aliens",
                "Alternate Universe", "American Football", "Amnesia", "Amputation",
                "Anachronism", "Anal Sex", "Ancient China", "Angels", "Animals",
                "Anthology", "Anthropomorphism", "Anti-Hero", "Archery", "Armpits",
                "Aromantic", "Arranged Marriage", "Artificial Intelligence", "Asexual",
                "Ashikoki", "Asphyxiation", "Assassins", "Astronomy", "Athletics",
                "Augmented Reality", "Autobiographical", "Aviation", "Badminton", "Ballet",
                "Band", "Bar", "Baseball", "Basketball", "Battle Royale", "Biographical",
                "Bisexual", "Blackmail", "Board Game", "Boarding School", "Body Horror",
                "Body Image", "Body Swapping", "Bondage", "Boobjob", "Bowling",
                "Boxing", "Boys' Love", "Brainwashing", "Bullying", "Butler",
                "CGI", "Calligraphy", "Camping", "Cannibalism", "Card Battle",
                "Cars", "Centaur", "Cervix Penetration", "Cheating", "Cheerleading",
                "Chibi", "Chimera", "Chuunibyou", "Circus", "Class Struggle",
                "Classic Literature", "Classical Music", "Clone", "Coastal", "Cohabitation",
                "College", "Coming of Age", "Conspiracy", "Cosmic Horror", "Cosplay",
                "Cowboys", "Creature Taming", "Crime", "Criminal Organization", "Crossdressing",
                "Crossover", "Cult", "Cultivation", "Cumflation", "Cunnilingus",
                "Curses", "Cute Boys Doing Cute Things", "Cute Girls Doing Cute Things", "Cyberpunk", "Cyborg",
                "Cycling", "DILF", "Dancing", "Death Game", "Deepthroat",
                "Defloration", "Delinquents", "Demons", "Denpa", "Desert",
                "Detective", "Dinosaurs", "Disability", "Dissociative Identities", "Double Penetration",
                "Dragons", "Drawing", "Drugs", "Dullahan", "Dungeon",
                "Dystopian", "E-Sports", "Eco-Horror", "Economics", "Educational",
                "Elderly Protagonist", "Elf", "Ensemble Cast", "Environmental", "Episodic",
                "Ero Guro", "Espionage", "Estranged Family", "Exhibitionism", "Exorcism",
                "Facial", "Fairy", "Fairy Tale", "Fake Relationship", "Family Life",
                "Fashion", "Feet", "Fellatio", "Female Harem", "Female Protagonist",
                "Femboy", "Femdom", "Fencing", "Filmmaking", "Fingering",
                "Firefighters", "Fishing", "Fisting", "Fitness", "Flash",
                "Flat Chest", "Food", "Football", "Foreign", "Found Family",
                "Fugitive", "Full CGI", "Futanari", "Gambling", "Gangs",
                "Gender Bending", "Ghost", "Go", "Goblin", "Gods",
                "Golf", "Gore", "Graduation Project", "Group Sex", "Guns",
                "Gyaru", "Hair Pulling", "Handball", "Handjob", "Henshin",
                "Heterosexual", "Hikikomori", "Hip-hop Music", "Historical", "Homeless",
                "Horticulture", "Human Experimentation", "Human Pet", "Hypersexuality", "Ice Skating",
                "Idol", "Incest", "Indigenous Cultures", "Inn", "Inseki",
                "Interspecies", "Irrumatio", "Isekai", "Iyashikei", "Jazz Music",
                "Josei", "Judo", "Kabuki", "Kaiju", "Karuta",
                "Kemonomimi", "Kids", "Kingdom Management", "Konbini", "Kuudere",
                "LGBTQ+ Themes", "Lacrosse", "Lactation", "Language Barrier", "Large Breasts",
                "Long Strip", "Lost Civilization", "Love Triangle", "MILF", "Mafia",
                "Magic", "Mahjong", "Maids", "Makeup", "Male Harem",
                "Male Pregnancy", "Male Protagonist", "Manzai", "Marriage", "Martial Arts",
                "Masochism", "Masturbation", "Matchmaking", "Mating Press", "Matriarchy",
                "Medicine", "Medieval", "Memory Manipulation", "Mermaid", "Meta",
                "Metal Music", "Military", "Mixed Gender Harem", "Mixed Media", "Modeling",
                "Monster Boy", "Monster Girl", "Mopeds", "Motorcycles", "Mountaineering",
                "Musical Theater", "Mythology", "Nakadashi", "Natural Disaster", "Necromancy",
                "Nekomimi", "Netorare", "Netorase", "Netori", "Ninja",
                "No Dialogue", "Noir", "Non-fiction", "Nudity", "Nun",
                "Office", "Office Lady", "Oiran", "Ojou-sama", "Omegaverse",
                "Orphan", "Otaku Culture", "Outdoor Activities", "Oyakodon", "POV",
                "Pandemic", "Parenthood", "Parkour", "Parody", "Pet Play",
                "Philosophy", "Photography", "Pirates", "Poker", "Police",
                "Politics", "Polyamorous", "Post-Apocalyptic", "Pregnancy", "Primarily Adult Cast",
                "Primarily Animal Cast", "Primarily Child Cast", "Primarily Female Cast", "Primarily Male Cast", "Primarily Teen Cast",
                "Prison", "Prostitution", "Proxy Battle", "Psychosexual", "Public Sex",
                "Puppetry", "Rakugo", "Rape", "Real Robot", "Rehabilitation",
                "Reincarnation", "Religion", "Restaurant", "Revenge", "Reverse Isekai",
                "Rimjob", "Robots", "Rock Music", "Rotoscoping", "Royal Affairs",
                "Rugby", "Rural", "Sadism", "Samurai", "Satire",
                "Scat", "School", "School Club", "Scissoring", "Scuba Diving",
                "Seinen", "Sex Toys", "Shapeshifting", "Shimaidon", "Ships",
                "Shogi", "Shoujo", "Shounen", "Shrine Maiden", "Skateboarding",
                "Skeleton", "Slapstick", "Slavery", "Snowscape", "Software Development",
                "Space", "Space Opera", "Spearplay", "Squirting", "Steampunk",
                "Stop Motion", "Succubus", "Suicide", "Sumata", "Sumo",
                "Super Power", "Super Robot", "Superhero", "Surfing", "Surreal Comedy",
                "Survival", "Sweat", "Swimming", "Swordplay", "Table Tennis",
                "Tanks", "Tanned Skin", "Teacher", "Teens' Love", "Tennis",
                "Tentacles", "Terrorism", "Threesome", "Time Loop", "Time Manipulation",
                "Time Skip", "Tokusatsu", "Tomboy", "Torture", "Tragedy",
                "Trains", "Transgender", "Travel", "Triads", "Tsundere",
                "Twins", "Unrequited Love", "Urban", "Urban Fantasy", "VTuber",
                "Vampire", "Vertical Video", "Veterinarian", "Video Games", "Vikings",
                "Villainess", "Virginity", "Virtual World", "Vocal Synth", "Volleyball",
                "Vore", "Voyeur", "War", "Watersports", "Werewolf",
                "Wilderness", "Witch", "Work", "Wrestling", "Writing",
                "Wuxia", "Yakuza", "Yandere", "Youkai", "Yuri",
                "Zombie", "Zoophilia",
            )
        }
    }

    val FILTER_LIST get() = AnimeFilterList(
        SortFilter(),
        FormatFilter(),
        StatusFilter(),
        SeasonFilter(),
        YearFilter(),
        GenreFilter(),
        CharacterFilter(),
        StaffFilter(),
        StudioFilter(),
        TagFilter(),
    )
}
