package eu.kanade.tachiyomi.animeextension.en.anikage

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object AnikageFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String = (this.getFirst<R>() as QueryPartFilter).toQueryPart()

    private inline fun <reified R> AnimeFilterList.getFirst(): R = this.filterIsInstance<R>().first()

    class OriginFilter : QueryPartFilter("Origin", AnikageFiltersData.ORIGIN)
    class SeasonFilter : QueryPartFilter("Season", AnikageFiltersData.SEASONS)
    class ReleaseYearFilter : QueryPartFilter("Released at", AnikageFiltersData.YEARS)
    class SortByFilter : QueryPartFilter("Sort By", AnikageFiltersData.SORT_BY)
    class TypesFilter : QueryPartFilter("Type", AnikageFiltersData.TYPES)

    class GenresFilter :
        CheckBoxFilterList(
            "Genres",
            AnikageFiltersData.GENRES.map { CheckBoxVal(it.first, false) },
        )

    inline fun <reified T : CheckBoxFilterList> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> = (firstOrNull { it is T } as? T)
        ?.state
        ?.mapIndexedNotNull { index, checked ->
            if (checked.state) options[index].second else null
        }
        ?: emptyList()

    val FILTER_LIST get() = AnimeFilterList(
        OriginFilter(),
        SeasonFilter(),
        ReleaseYearFilter(),
        SortByFilter(),
        AnimeFilter.Separator(),
        TypesFilter(),
        GenresFilter(),
    )

    data class FilterSearchParams(
        val origin: String = "",
        val season: String = "",
        val releaseYear: String = "",
        val sortBy: String = "",
        val types: String = "",
        val genres: List<String> = emptyList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            filters.asQueryPart<OriginFilter>(),
            filters.asQueryPart<SeasonFilter>(),
            filters.asQueryPart<ReleaseYearFilter>(),
            filters.asQueryPart<SortByFilter>(),
            filters.asQueryPart<TypesFilter>(),
            filters.parseCheckbox<GenresFilter>(AnikageFiltersData.GENRES),
        )
    }

    private object AnikageFiltersData {
        val ALL = Pair("All", "ALL")
        val ORIGIN = arrayOf(
            Pair("ALL", "ALL"),
            Pair("Japan", "JP"),
            Pair("Korea", "KR"),
            Pair("China", "CN"),
            Pair("Taiwan", "TW"),
        )
        val SEASONS = arrayOf(
            Pair("ALL", "ALL"),
            Pair("Winter", "WINTER"),
            Pair("Spring", "SPRING"),
            Pair("Summer", "SUMMER"),
            Pair("Fall", "FALL"),
        )

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        val YEARS: Array<Pair<String, String>> =
            (currentYear + 1 downTo 1940)
                .map { year -> year.toString() to year.toString() }
                .toTypedArray()

        val SORT_BY = arrayOf(
            Pair("Title", "TITLE_ENGLISH"),
            Pair("Popularity", "POPULARITY_DESC"),
            Pair("Average Score", "SCORE_DESC"),
            Pair("Trending", "TRENDING_DESC"),
            Pair("Favorites", "FAVOURITES_DESC"),
            Pair("Date Added", "ID_DESC"),
            Pair("Release Date", "START_DATE_DESC"),
        )

        val TYPES = arrayOf(
            Pair("ALL", "ALL"),
            Pair("TV", "TV"),
            Pair("TV Short", "TV_SHORT"),
            Pair("Movie", "MOVIE"),
            Pair("Special", "SPECIAL"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("MUSIC", "MUSIC"),
        )

        val GENRES = arrayOf(
            Pair("Anime", "ANIME"),
            Pair("Manga", "MANGA"),
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
            Pair("4-koma", "4-koma"),
            Pair("Achronological Order", "Achronological Order"),
            Pair("Afterlife", "Afterlife"),
            Pair("Age Gap", "Age Gap"),
            Pair("Airsoft", "Airsoft"),
            Pair("Aliens", "Aliens"),
            Pair("Alternate Universe", "Alternate Universe"),
            Pair("American Football", "American Football"),
            Pair("Amnesia", "Amnesia"),
            Pair("Anti-Hero", "Anti-Hero"),
            Pair("Archery", "Archery"),
            Pair("Assassins", "Assassins"),
            Pair("Athletics", "Athletics"),
            Pair("Augmented Reality", "Augmented Reality"),
            Pair("Aviation", "Aviation"),
            Pair("Badminton", "Badminton"),
            Pair("Band", "Band"),
            Pair("Bar", "Bar"),
            Pair("Baseball", "Baseball"),
            Pair("Basketball", "Basketball"),
            Pair("Battle Royale", "Battle Royale"),
            Pair("Biographical", "Biographical"),
            Pair("Bisexual", "Bisexual"),
            Pair("Body Swapping", "Body Swapping"),
            Pair("Boxing", "Boxing"),
            Pair("Bullying", "Bullying"),
            Pair("Calligraphy", "Calligraphy"),
            Pair("Card Battle", "Card Battle"),
            Pair("Cars", "Cars"),
            Pair("CGI", "CGI"),
            Pair("Chibi", "Chibi"),
            Pair("Chuunibyou", "Chuunibyou"),
            Pair("Classic Literature", "Classic Literature"),
            Pair("College", "College"),
            Pair("Coming of Age", "Coming of Age"),
            Pair("Cosplay", "Cosplay"),
            Pair("Crossdressing", "Crossdressing"),
            Pair("Crossover", "Crossover"),
            Pair("Cultivation", "Cultivation"),
            Pair("Curses", "Curses"),
            Pair("Cute Girls Doing Cute Things", "Cute Girls Doing Cute Things"),
            Pair("Cyberpunk", "Cyberpunk"),
            Pair("Cycling", "Cycling"),
            Pair("Dancing", "Dancing"),
            Pair("Delinquents", "Delinquents"),
            Pair("Demons", "Demons"),
            Pair("Development", "Development"),
            Pair("Dragons", "Dragons"),
            Pair("Drawing", "Drawing"),
            Pair("Dystopian", "Dystopian"),
            Pair("Economics", "Economics"),
            Pair("Educational", "Educational"),
            Pair("Ensemble Cast", "Ensemble Cast"),
            Pair("Environmental", "Environmental"),
            Pair("Episodic", "Episodic"),
            Pair("Espionage", "Espionage"),
            Pair("Fairy Tale", "Fairy Tale"),
            Pair("Family Life", "Family Life"),
            Pair("Fashion", "Fashion"),
            Pair("Female Protagonist", "Female Protagonist"),
            Pair("Fishing", "Fishing"),
            Pair("Fitness", "Fitness"),
            Pair("Flash", "Flash"),
            Pair("Food", "Food"),
            Pair("Football", "Football"),
            Pair("Foreign", "Foreign"),
            Pair("Fugitive", "Fugitive"),
            Pair("Full CGI", "Full CGI"),
            Pair("Full Colour", "Full Colour"),
            Pair("Gambling", "Gambling"),
            Pair("Gangs", "Gangs"),
            Pair("Gender Bending", "Gender Bending"),
            Pair("Gender Neutral", "Gender Neutral"),
            Pair("Ghost", "Ghost"),
            Pair("Gods", "Gods"),
            Pair("Gore", "Gore"),
            Pair("Guns", "Guns"),
            Pair("Gyaru", "Gyaru"),
            Pair("Harem", "Harem"),
            Pair("Henshin", "Henshin"),
            Pair("Hikikomori", "Hikikomori"),
            Pair("Historical", "Historical"),
            Pair("Ice Skating", "Ice Skating"),
            Pair("Idol", "Idol"),
            Pair("Isekai", "Isekai"),
            Pair("Iyashikei", "Iyashikei"),
            Pair("Josei", "Josei"),
            Pair("Kaiju", "Kaiju"),
            Pair("Karuta", "Karuta"),
            Pair("Kemonomimi", "Kemonomimi"),
            Pair("Kids", "Kids"),
            Pair("Love Triangle", "Love Triangle"),
            Pair("Mafia", "Mafia"),
            Pair("Magic", "Magic"),
            Pair("Mahjong", "Mahjong"),
            Pair("Maids", "Maids"),
            Pair("Male Protagonist", "Male Protagonist"),
            Pair("Martial Arts", "Martial Arts"),
            Pair("Memory Manipulation", "Memory Manipulation"),
            Pair("Meta", "Meta"),
            Pair("Military", "Military"),
            Pair("Monster Girl", "Monster Girl"),
            Pair("Mopeds", "Mopeds"),
            Pair("Motorcycles", "Motorcycles"),
            Pair("Musical", "Musical"),
            Pair("Mythology", "Mythology"),
            Pair("Nekomimi", "Nekomimi"),
            Pair("Ninja", "Ninja"),
            Pair("No Dialogue", "No Dialogue"),
            Pair("Noir", "Noir"),
            Pair("Nudity", "Nudity"),
            Pair("Otaku Culture", "Otaku Culture"),
            Pair("Outdoor", "Outdoor"),
            Pair("Parody", "Parody"),
            Pair("Philosophy", "Philosophy"),
            Pair("Photography", "Photography"),
            Pair("Pirates", "Pirates"),
            Pair("Poker", "Poker"),
            Pair("Police", "Police"),
            Pair("Politics", "Politics"),
            Pair("Post-Apocalyptic", "Post-Apocalyptic"),
            Pair("Primarily Adult Cast", "Primarily Adult Cast"),
            Pair("Primarily Female Cast", "Primarily Female Cast"),
            Pair("Primarily Male Cast", "Primarily Male Cast"),
            Pair("Puppetry", "Puppetry"),
            Pair("Real Robot", "Real Robot"),
            Pair("Rehabilitation", "Rehabilitation"),
            Pair("Reincarnation", "Reincarnation"),
            Pair("Revenge", "Revenge"),
            Pair("Reverse Harem", "Reverse Harem"),
            Pair("Robots", "Robots"),
            Pair("Rugby", "Rugby"),
            Pair("Rural", "Rural"),
            Pair("Samurai", "Samurai"),
            Pair("Satire", "Satire"),
            Pair("School", "School"),
            Pair("School Club", "School Club"),
            Pair("Seinen", "Seinen"),
            Pair("Ships", "Ships"),
            Pair("Shogi", "Shogi"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shoujo Ai", "Shoujo Ai"),
            Pair("Shounen", "Shounen"),
            Pair("Shounen Ai", "Shounen Ai"),
            Pair("Slapstick", "Slapstick"),
            Pair("Slavery", "Slavery"),
            Pair("Space", "Space"),
            Pair("Space Opera", "Space Opera"),
            Pair("Steampunk", "Steampunk"),
            Pair("Stop Motion", "Stop Motion"),
            Pair("Super Power", "Super Power"),
            Pair("Super Robot", "Super Robot"),
            Pair("Superhero", "Superhero"),
            Pair("Surreal Comedy", "Surreal Comedy"),
            Pair("Survival", "Survival"),
            Pair("Swimming", "Swimming"),
            Pair("Swordplay", "Swordplay"),
            Pair("Table Tennis", "Table Tennis"),
            Pair("Tanks", "Tanks"),
            Pair("Teacher", "Teacher"),
            Pair("Tennis", "Tennis"),
            Pair("Terrorism", "Terrorism"),
            Pair("Time Manipulation", "Time Manipulation"),
            Pair("Time Skip", "Time Skip"),
            Pair("Tragedy", "Tragedy"),
            Pair("Trains", "Trains"),
            Pair("Triads", "Triads"),
            Pair("Tsundere", "Tsundere"),
            Pair("Urban Fantasy", "Urban Fantasy"),
            Pair("Vampire", "Vampire"),
            Pair("Video Games", "Video Games"),
            Pair("Virtual World", "Virtual World"),
            Pair("Volleyball", "Volleyball"),
            Pair("War", "War"),
            Pair("Witch", "Witch"),
            Pair("Work", "Work"),
            Pair("Wrestling", "Wrestling"),
            Pair("Writing", "Writing"),
            Pair("Wuxia", "Wuxia"),
            Pair("Yakuza", "Yakuza"),
            Pair("Yandere", "Yandere"),
            Pair("Youkai", "Youkai"),
            Pair("Zombie", "Zombie"),
        )
    }
}
