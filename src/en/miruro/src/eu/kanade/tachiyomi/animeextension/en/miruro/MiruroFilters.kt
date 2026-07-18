package eu.kanade.tachiyomi.animeextension.en.miruro

import aniyomi.lib.anilib.MediaFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object MiruroFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class TriStateFilterList(name: String, values: List<TriFilterVal>) : AnimeFilter.Group<TriState>(name, values)
    class TriFilterVal(name: String) : TriState(name)

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String = (this.getFirst<R>() as QueryPartFilter).toQueryPart()

    private inline fun <reified R> AnimeFilterList.getFirst(): R = this.filterIsInstance<R>().first()

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> = (this.getFirst<R>() as CheckBoxFilterList).state
        .mapNotNull { checkbox ->
            if (checkbox.state) {
                options.find { it.first == checkbox.name }?.second
            } else {
                null
            }
        }

    private inline fun <reified R> AnimeFilterList.parseTriState(
        options: Array<Pair<String, String>>,
    ): Pair<List<String>, List<String>> {
        val state = (this.getFirst<R>() as TriStateFilterList).state
        val included = mutableListOf<String>()
        val excluded = mutableListOf<String>()
        for (filter in state) {
            if (filter.isIgnored()) continue
            val value = options.find { it.first == filter.name }?.second ?: continue
            when (filter.state) {
                TriState.STATE_INCLUDE -> included.add(value)
                TriState.STATE_EXCLUDE -> excluded.add(value)
            }
        }
        return included to excluded
    }

    class SortFilter : QueryPartFilter("Sort", MiruroFiltersData.SORT)

    class GenreFilter :
        TriStateFilterList(
            "Genres",
            MiruroFiltersData.GENRES.map { TriFilterVal(it.first) },
        )

    class YearFilter : QueryPartFilter("Year", MiruroFiltersData.YEARS)

    class SeasonFilter : QueryPartFilter("Season", MiruroFiltersData.SEASONS)

    class StatusFilter : QueryPartFilter("Status", MiruroFiltersData.STATUS)

    class FormatFilter :
        CheckBoxFilterList(
            "Format",
            MiruroFiltersData.FORMATS.map { CheckBoxVal(it.first, false) },
        )

    class TagsFilter :
        TriStateFilterList(
            "Tags",
            MiruroFiltersData.TAGS.map { TriFilterVal(it.first) },
        )

    class DubLanguageFilter : QueryPartFilter("Dub Language", MiruroFiltersData.DUB_LANGUAGES)

    val FILTER_LIST get() = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
        TagsFilter(),
        FormatFilter(),
        AnimeFilter.Separator(),
        YearFilter(),
        SeasonFilter(),
        StatusFilter(),
        DubLanguageFilter(),
    )

    data class FilterSearchParams(
        val sort: String = "all",
        val genres: List<String> = emptyList(),
        val excludedGenres: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val excludedTags: List<String> = emptyList(),
        val year: String = "all",
        val season: String = "all",
        val status: String = "all",
        val formats: List<String> = emptyList(),
        val dubLanguage: String = "all",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        val (includedGenres, excludedGenres) = filters.parseTriState<GenreFilter>(MiruroFiltersData.GENRES)
        val (includedTags, excludedTags) = filters.parseTriState<TagsFilter>(MiruroFiltersData.TAGS)
        return FilterSearchParams(
            sort = filters.asQueryPart<SortFilter>(),
            genres = includedGenres,
            excludedGenres = excludedGenres,
            tags = includedTags,
            excludedTags = excludedTags,
            year = filters.asQueryPart<YearFilter>(),
            season = filters.asQueryPart<SeasonFilter>(),
            status = filters.asQueryPart<StatusFilter>(),
            formats = filters.parseCheckbox<FormatFilter>(MiruroFiltersData.FORMATS),
            dubLanguage = filters.asQueryPart<DubLanguageFilter>(),
        )
    }

    private object MiruroFiltersData {

        val ALL = Pair("All", "all")

        val SORT = arrayOf(
            ALL,
            Pair("Trending", "TRENDING_DESC"),
            Pair("Popularity", "POPULARITY_DESC"),
            Pair("Average Score", "SCORE_DESC"),
            Pair("Favorites", "FAVOURITES_DESC"),
            Pair("Latest", "START_DATE_DESC"),
            Pair("Title A-Z", "TITLE_ROMAJI"),
            Pair("Title Z-A", "TITLE_ROMAJI_DESC"),
        )

        val GENRES = arrayOf(
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Comedy", "Comedy"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Horror", "Horror"),
            Pair("Mahou Shoujo", "Mahou Shoujo"),
            Pair("Mecha", "Mecha"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Sports", "Sports"),
            Pair("Supernatural", "Supernatural"),
            Pair("Thriller", "Thriller"),
        )

        val YEARS = arrayOf(ALL) + (Calendar.getInstance().get(Calendar.YEAR) downTo 1940).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val SEASONS = arrayOf(
            ALL,
            Pair("Winter", "WINTER"),
            Pair("Spring", "SPRING"),
            Pair("Summer", "SUMMER"),
            Pair("Fall", "FALL"),
        )

        val STATUS = arrayOf(
            ALL,
            Pair("Airing", "RELEASING"),
            Pair("Finished", "FINISHED"),
            Pair("Not Yet Aired", "NOT_YET_RELEASED"),
            Pair("Hiatus", "HIATUS"),
            Pair("Cancelled", "CANCELLED"),
        )

        val FORMATS = arrayOf(
            Pair("TV", "TV"),
            Pair("TV Short", "TV_SHORT"),
            Pair("Movie", "MOVIE"),
            Pair("Special", "SPECIAL"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Music", "MUSIC"),
        )

        val DUB_LANGUAGES = arrayOf(
            ALL,
            Pair("English", "English"),
            Pair("Japanese", "Japanese"),
            Pair("Spanish", "Español"),
            Pair("Portuguese", "Português"),
            Pair("French", "Français"),
            Pair("German", "Deutsch"),
            Pair("Italian", "Italiano"),
            Pair("Korean", "한국어"),
            Pair("Chinese", "中文"),
            Pair("Arabic", "العربية"),
            Pair("Hindi", "हिन्दी"),
            Pair("Russian", "Русский"),
            Pair("Turkish", "Türkçe"),
            Pair("Thai", "ไทย"),
            Pair("Polish", "Polski"),
            Pair("Tagalog", "Tagalog"),
            Pair("Ukrainian", "Українська"),
        )

        val TAGS = arrayOf(
            Pair("Achromatic", "Achromatic"),
            Pair("Achronological Order", "Achronological Order"),
            Pair("Acrobatics", "Acrobatics"),
            Pair("Acting", "Acting"),
            Pair("Adoption", "Adoption"),
            Pair("Advertisement", "Advertisement"),
            Pair("Afterlife", "Afterlife"),
            Pair("Age Gap", "Age Gap"),
            Pair("Age Regression", "Age Regression"),
            Pair("Agender", "Agender"),
            Pair("Agriculture", "Agriculture"),
            Pair("Airsoft", "Airsoft"),
            Pair("Alchemy", "Alchemy"),
            Pair("Aliens", "Aliens"),
            Pair("Alternate Universe", "Alternate Universe"),
            Pair("American Football", "American Football"),
            Pair("Amnesia", "Amnesia"),
            Pair("Anachronism", "Anachronism"),
            Pair("Ancient China", "Ancient China"),
            Pair("Angels", "Angels"),
            Pair("Animals", "Animals"),
            Pair("Anthology", "Anthology"),
            Pair("Anthropomorphism", "Anthropomorphism"),
            Pair("Anti-Hero", "Anti-Hero"),
            Pair("Archery", "Archery"),
            Pair("Aromantic", "Aromantic"),
            Pair("Arranged Marriage", "Arranged Marriage"),
            Pair("Artificial Intelligence", "Artificial Intelligence"),
            Pair("Assassins", "Assassins"),
            Pair("Astronomy", "Astronomy"),
            Pair("Athletics", "Athletics"),
            Pair("Augmented Reality", "Augmented Reality"),
            Pair("Autobiographical", "Autobiographical"),
            Pair("Aviation", "Aviation"),
            Pair("Badminton", "Badminton"),
            Pair("Band", "Band"),
            Pair("Bar", "Bar"),
            Pair("Baseball", "Baseball"),
            Pair("Basketball", "Basketball"),
            Pair("Battle Royale", "Battle Royale"),
            Pair("Biographical", "Biographical"),
            Pair("Board Game", "Board Game"),
            Pair("Boarding School", "Boarding School"),
            Pair("Body Horror", "Body Horror"),
            Pair("Body Swapping", "Body Swapping"),
            Pair("Bowling", "Bowling"),
            Pair("Boxing", "Boxing"),
            Pair("Boys Love", "Boys Love"),
            Pair("Bullying", "Bullying"),
            Pair("Butler", "Butler"),
            Pair("Calligraphy", "Calligraphy"),
            Pair("Cannibalism", "Cannibalism"),
            Pair("Card Battle", "Card Battle"),
            Pair("Cars", "Cars"),
            Pair("Centaur", "Centaur"),
            Pair("CGI", "CGI"),
            Pair("Cheerleading", "Cheerleading"),
            Pair("Chibi", "Chibi"),
            Pair("Chimera", "Chimera"),
            Pair("Chuunibyou", "Chuunibyou"),
            Pair("Circus", "Circus"),
            Pair("Class Struggle", "Class Struggle"),
            Pair("Classic Literature", "Classic Literature"),
            Pair("Classical Music", "Classical Music"),
            Pair("Clone", "Clone"),
            Pair("Coastal", "Coastal"),
            Pair("College", "College"),
            Pair("Coming of Age", "Coming of Age"),
            Pair("Conspiracy", "Conspiracy"),
            Pair("Cosmic Horror", "Cosmic Horror"),
            Pair("Cosplay", "Cosplay"),
            Pair("Cowboys", "Cowboys"),
            Pair("Crime", "Crime"),
            Pair("Criminal Organization", "Criminal Organization"),
            Pair("Crossdressing", "Crossdressing"),
            Pair("Crossover", "Crossover"),
            Pair("Cult", "Cult"),
            Pair("Cultivation", "Cultivation"),
            Pair("Cute Boys Doing Cute Things", "Cute Boys Doing Cute Things"),
            Pair("Cute Girls Doing Cute Things", "Cute Girls Doing Cute Things"),
            Pair("Cyberpunk", "Cyberpunk"),
            Pair("Cyborg", "Cyborg"),
            Pair("Cycling", "Cycling"),
            Pair("Dancing", "Dancing"),
            Pair("Death Game", "Death Game"),
            Pair("Delinquents", "Delinquents"),
            Pair("Demons", "Demons"),
            Pair("Denpa", "Denpa"),
            Pair("Desert", "Desert"),
            Pair("Detective", "Detective"),
            Pair("Dinosaurs", "Dinosaurs"),
            Pair("Disability", "Disability"),
            Pair("Dissociative Identities", "Dissociative Identities"),
            Pair("Dragons", "Dragons"),
            Pair("Drawing", "Drawing"),
            Pair("Drugs", "Drugs"),
            Pair("Dullahan", "Dullahan"),
            Pair("Dungeon", "Dungeon"),
            Pair("Dystopian", "Dystopian"),
            Pair("E-Sports", "E-Sports"),
            Pair("Eco-Horror", "Eco-Horror"),
            Pair("Economics", "Economics"),
            Pair("Educational", "Educational"),
            Pair("Elderly Protagonist", "Elderly Protagonist"),
            Pair("Elf", "Elf"),
            Pair("Ensemble Cast", "Ensemble Cast"),
            Pair("Environmental", "Environmental"),
            Pair("Episodic", "Episodic"),
            Pair("Ero Guro", "Ero Guro"),
            Pair("Espionage", "Espionage"),
            Pair("Estranged Family", "Estranged Family"),
            Pair("Fairy", "Fairy"),
            Pair("Fairy Tale", "Fairy Tale"),
            Pair("Fake Relationship", "Fake Relationship"),
            Pair("Family Life", "Family Life"),
            Pair("Fashion", "Fashion"),
            Pair("Female Harem", "Female Harem"),
            Pair("Female Protagonist", "Female Protagonist"),
            Pair("Femboy", "Femboy"),
            Pair("Fencing", "Fencing"),
            Pair("Filmmaking", "Filmmaking"),
            Pair("Firefighters", "Firefighters"),
            Pair("Fishing", "Fishing"),
            Pair("Fitness", "Fitness"),
            Pair("Flash", "Flash"),
            Pair("Food", "Food"),
            Pair("Football", "Football"),
            Pair("Foreign", "Foreign"),
            Pair("Found Family", "Found Family"),
            Pair("Fugitive", "Fugitive"),
            Pair("Full CGI", "Full CGI"),
            Pair("Full Color", "Full Color"),
            Pair("Gambling", "Gambling"),
            Pair("Gangs", "Gangs"),
            Pair("Gender Bending", "Gender Bending"),
            Pair("Ghost", "Ghost"),
            Pair("Go", "Go"),
            Pair("Goblin", "Goblin"),
            Pair("Gods", "Gods"),
            Pair("Golf", "Golf"),
            Pair("Gore", "Gore"),
            Pair("Guns", "Guns"),
            Pair("Gyaru", "Gyaru"),
            Pair("Handball", "Handball"),
            Pair("Henshin", "Henshin"),
            Pair("Hikikomori", "Hikikomori"),
            Pair("Hip-hop Music", "Hip-hop Music"),
            Pair("Historical", "Historical"),
            Pair("Homeless", "Homeless"),
            Pair("Horticulture", "Horticulture"),
            Pair("Ice Skating", "Ice Skating"),
            Pair("Idol", "Idol"),
            Pair("Inn", "Inn"),
            Pair("Isekai", "Isekai"),
            Pair("Iyashikei", "Iyashikei"),
            Pair("Jazz Music", "Jazz Music"),
            Pair("Josei", "Josei"),
            Pair("Judo", "Judo"),
            Pair("Kaiju", "Kaiju"),
            Pair("Karuta", "Karuta"),
            Pair("Kemonomimi", "Kemonomimi"),
            Pair("Kids", "Kids"),
            Pair("Kingdom Management", "Kingdom Management"),
            Pair("Konbini", "Konbini"),
            Pair("Kuudere", "Kuudere"),
            Pair("Lacrosse", "Lacrosse"),
            Pair("Language Barrier", "Language Barrier"),
            Pair("Lost Civilization", "Lost Civilization"),
            Pair("Love Triangle", "Love Triangle"),
            Pair("Mafia", "Mafia"),
            Pair("Magic", "Magic"),
            Pair("Mahjong", "Mahjong"),
            Pair("Maids", "Maids"),
            Pair("Makeup", "Makeup"),
            Pair("Male Harem", "Male Harem"),
            Pair("Male Protagonist", "Male Protagonist"),
            Pair("Marriage", "Marriage"),
            Pair("Martial Arts", "Martial Arts"),
            Pair("Matchmaking", "Matchmaking"),
            Pair("Matriarchy", "Matriarchy"),
            Pair("Medicine", "Medicine"),
            Pair("Memory Manipulation", "Memory Manipulation"),
            Pair("Mermaid", "Mermaid"),
            Pair("Meta", "Meta"),
            Pair("Metal Music", "Metal Music"),
            Pair("Military", "Military"),
            Pair("Mixed Gender Harem", "Mixed Gender Harem"),
            Pair("Monster Boy", "Monster Boy"),
            Pair("Monster Girl", "Monster Girl"),
            Pair("Mopeds", "Mopeds"),
            Pair("Motorcycles", "Motorcycles"),
            Pair("Mountaineering", "Mountaineering"),
            Pair("Musical Theater", "Musical Theater"),
            Pair("Mythology", "Mythology"),
            Pair("Natural Disaster", "Natural Disaster"),
            Pair("Necromancy", "Necromancy"),
            Pair("Nekomimi", "Nekomimi"),
            Pair("Ninja", "Ninja"),
            Pair("No Dialogue", "No Dialogue"),
            Pair("Noir", "Noir"),
            Pair("Non-fiction", "Non-fiction"),
            Pair("Nudity", "Nudity"),
            Pair("Nun", "Nun"),
            Pair("Office", "Office"),
            Pair("Office Lady", "Office Lady"),
            Pair("Oiran", "Oiran"),
            Pair("Ojou-sama", "Ojou-sama"),
            Pair("Orphan", "Orphan"),
            Pair("Otaku Culture", "Otaku Culture"),
            Pair("Outdoor", "Outdoor"),
            Pair("Pandemic", "Pandemic"),
            Pair("Parenthood", "Parenthood"),
            Pair("Parkour", "Parkour"),
            Pair("Parody", "Parody"),
            Pair("Philosophy", "Philosophy"),
            Pair("Photography", "Photography"),
            Pair("Pirates", "Pirates"),
            Pair("Poker", "Poker"),
            Pair("Police", "Police"),
            Pair("Politics", "Politics"),
            Pair("Polyamorous", "Polyamorous"),
            Pair("Post-Apocalyptic", "Post-Apocalyptic"),
            Pair("POV", "POV"),
            Pair("Primarily Adult Cast", "Primarily Adult Cast"),
            Pair("Primarily Animal Cast", "Primarily Animal Cast"),
            Pair("Primarily Child Cast", "Primarily Child Cast"),
            Pair("Primarily Female Cast", "Primarily Female Cast"),
            Pair("Primarily Male Cast", "Primarily Male Cast"),
            Pair("Primarily Teen Cast", "Primarily Teen Cast"),
            Pair("Prison", "Prison"),
            Pair("Proxy Battle", "Proxy Battle"),
            Pair("Puppetry", "Puppetry"),
            Pair("Rakugo", "Rakugo"),
            Pair("Real Robot", "Real Robot"),
            Pair("Rehabilitation", "Rehabilitation"),
            Pair("Reincarnation", "Reincarnation"),
            Pair("Religion", "Religion"),
            Pair("Restaurant", "Restaurant"),
            Pair("Revenge", "Revenge"),
            Pair("Robots", "Robots"),
            Pair("Rock Music", "Rock Music"),
            Pair("Rotoscoping", "Rotoscoping"),
            Pair("Royal Affairs", "Royal Affairs"),
            Pair("Rugby", "Rugby"),
            Pair("Rural", "Rural"),
            Pair("Samurai", "Samurai"),
            Pair("Satire", "Satire"),
            Pair("School", "School"),
            Pair("School Club", "School Club"),
            Pair("Scuba Diving", "Scuba Diving"),
            Pair("Seinen", "Seinen"),
            Pair("Shapeshifting", "Shapeshifting"),
            Pair("Ships", "Ships"),
            Pair("Shogi", "Shogi"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shounen", "Shounen"),
            Pair("Shrine Maiden", "Shrine Maiden"),
            Pair("Skateboarding", "Skateboarding"),
            Pair("Skeleton", "Skeleton"),
            Pair("Slapstick", "Slapstick"),
            Pair("Slavery", "Slavery"),
            Pair("Snowscape", "Snowscape"),
            Pair("Software Development", "Software Development"),
            Pair("Space", "Space"),
            Pair("Space Opera", "Space Opera"),
            Pair("Spearplay", "Spearplay"),
            Pair("Steampunk", "Steampunk"),
            Pair("Stop Motion", "Stop Motion"),
            Pair("Succubus", "Succubus"),
            Pair("Suicide", "Suicide"),
            Pair("Sumo", "Sumo"),
            Pair("Super Power", "Super Power"),
            Pair("Super Robot", "Super Robot"),
            Pair("Superhero", "Superhero"),
            Pair("Surfing", "Surfing"),
            Pair("Surreal Comedy", "Surreal Comedy"),
            Pair("Survival", "Survival"),
            Pair("Swimming", "Swimming"),
            Pair("Swordplay", "Swordplay"),
            Pair("Table Tennis", "Table Tennis"),
            Pair("Tanks", "Tanks"),
            Pair("Tanned Skin", "Tanned Skin"),
            Pair("Teacher", "Teacher"),
            Pair("Teens Love", "Teens Love"),
            Pair("Tennis", "Tennis"),
            Pair("Terrorism", "Terrorism"),
            Pair("Time Loop", "Time Loop"),
            Pair("Time Manipulation", "Time Manipulation"),
            Pair("Time Skip", "Time Skip"),
            Pair("Tokusatsu", "Tokusatsu"),
            Pair("Tomboy", "Tomboy"),
            Pair("Torture", "Torture"),
            Pair("Tragedy", "Tragedy"),
            Pair("Trains", "Trains"),
            Pair("Transgender", "Transgender"),
            Pair("Travel", "Travel"),
            Pair("Triads", "Triads"),
            Pair("Tsundere", "Tsundere"),
            Pair("Twins", "Twins"),
            Pair("Unrequited Love", "Unrequited Love"),
            Pair("Urban", "Urban"),
            Pair("Urban Fantasy", "Urban Fantasy"),
            Pair("Vampire", "Vampire"),
            Pair("Veterinarian", "Veterinarian"),
            Pair("Video Games", "Video Games"),
            Pair("Vikings", "Vikings"),
            Pair("Villainess", "Villainess"),
            Pair("Virtual World", "Virtual World"),
            Pair("Volleyball", "Volleyball"),
            Pair("VTuber", "VTuber"),
            Pair("War", "War"),
            Pair("Werewolf", "Werewolf"),
            Pair("Witch", "Witch"),
            Pair("Work", "Work"),
            Pair("Wrestling", "Wrestling"),
            Pair("Writing", "Writing"),
            Pair("Wuxia", "Wuxia"),
            Pair("Yakuza", "Yakuza"),
            Pair("Yandere", "Yandere"),
            Pair("Youkai", "Youkai"),
            Pair("Yuri", "Yuri"),
            Pair("Zombie", "Zombie"),
        )
    }
}

fun MiruroFilters.FilterSearchParams.toMediaFilter(page: Int = 1, perPage: Int = 20): MediaFilter = MediaFilter(
    sort = sort.takeIf { it != "all" },
    genres = genres.takeIf { it.isNotEmpty() },
    excludedGenres = excludedGenres.takeIf { it.isNotEmpty() },
    tags = tags.takeIf { it.isNotEmpty() },
    excludedTags = excludedTags.takeIf { it.isNotEmpty() },
    seasonYear = year.toIntOrNull(),
    season = season.takeIf { it != "all" },
    status = status.takeIf { it != "all" },
    formatList = formats.takeIf { it.isNotEmpty() },
    page = page,
    perPage = perPage,
)
