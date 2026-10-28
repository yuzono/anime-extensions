package eu.kanade.tachiyomi.animeextension.en.anikage

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.network.get
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.Calendar

object Filters {

    private const val PREF_GENRES = "pref_dynamic_genres"
    private const val PREF_TAGS = "pref_dynamic_tags"

    private val APP_REGEX by lazy { Regex("""/_app/immutable/entry/(app\.[a-zA-Z0-9_\-]+\.js)|entry/(app\.[a-zA-Z0-9_\-]+\.js)""") }
    private val BROWSE_NODE_REGEX by lazy { Regex(""""/\(media\)/browse":\[(\d+)""") }
    private val BIFE_CHUNK_REGEX by lazy { Regex("""chunks/([a-zA-Z0-9_\-]*BIFE[a-zA-Z0-9_\-]*\.js)""") }
    private val CHUNK_IMPORT_REGEX by lazy { Regex("""from"\.\./chunks/([^"]+)"""") }

    private val GENRE_REGEX by lazy { Regex("""label:[`"']([^`"']+)[`"'],value:[`"']([^`"']+)[`"'],type:[`"']genres[`"']""") }
    private val TAG_REGEX by lazy { Regex("""label:[`"']([^`"']+)[`"'],value:[`"']([^`"']+)[`"'],type:[`"']tags[`"']""") }

    suspend fun fetchFilters(client: OkHttpClient, baseUrl: String, preferences: SharedPreferences, headers: Headers) {
        try {
            val fetchHeaders = headers.newBuilder()
                .set("Referer", "$baseUrl/")
                .build()

            val html = client.get("$baseUrl/browse", fetchHeaders).bodyString()
            val appFile = APP_REGEX.find(html)?.let { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
            } ?: return

            val appJs = client.get("$baseUrl/_app/immutable/entry/$appFile", fetchHeaders).bodyString()
            val browseNodeId = BROWSE_NODE_REGEX.find(appJs)?.groupValues?.get(1) ?: return
            val nodeFile = Regex("""nodes/$browseNodeId\.([a-zA-Z0-9_\-]+)\.js""").find(appJs)?.value ?: return

            val nodeJs = client.get("$baseUrl/_app/immutable/$nodeFile", fetchHeaders).bodyString()
            var chunkName = BIFE_CHUNK_REGEX.find(nodeJs)?.groupValues?.get(1)
            if (chunkName == null) {
                val matches = CHUNK_IMPORT_REGEX.findAll(nodeJs)
                for (match in matches) {
                    val candidate = match.groupValues[1]
                    val content = client.get("$baseUrl/_app/immutable/chunks/$candidate", fetchHeaders).bodyString()
                    if (content.contains("genres") && content.contains("tags")) {
                        chunkName = candidate
                        break
                    }
                }
            }
            if (chunkName == null) return

            val chunkJs = client.get("$baseUrl/_app/immutable/chunks/$chunkName", fetchHeaders).bodyString()

            val genres = GENRE_REGEX.findAll(chunkJs).map { it.groupValues[1] }.distinct().toList()
            val tags = TAG_REGEX.findAll(chunkJs).map { it.groupValues[1] }.distinct().toList()

            val editor = preferences.edit()
            if (genres.isNotEmpty()) editor.putString(PREF_GENRES, genres.joinToString("\n"))
            if (tags.isNotEmpty()) editor.putString(PREF_TAGS, tags.joinToString("\n"))
            editor.apply()
        } catch (_: Exception) {
            // Keep using cached or static filters
        }
    }

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

    class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String = (this.getFirst<R>() as QueryPartFilter).toQueryPart()

    private inline fun <reified R> AnimeFilterList.getFirst(): R = this.filterIsInstance<R>().first()

    class OriginFilter : QueryPartFilter("Origin", AnikageFiltersData.ORIGIN)
    class SeasonFilter : QueryPartFilter("Season", AnikageFiltersData.SEASONS)
    class ReleaseYearFilter : QueryPartFilter("Released at", AnikageFiltersData.YEARS)
    class SortByFilter : QueryPartFilter("Sort By", AnikageFiltersData.SORT_BY)
    class TypesFilter : QueryPartFilter("Type", AnikageFiltersData.TYPES)
    class StatusFilter : QueryPartFilter("Status", AnikageFiltersData.STATUS)

    class GenresFilter(genres: List<String>) : CheckBoxFilterList("Genres", genres.map { CheckBoxVal(it, false) })
    class TagsFilter(tags: List<String>) : CheckBoxFilterList("Tags / Tropes", tags.map { CheckBoxVal(it, false) })

    private fun AnimeFilterList.parseSelected(name: String): List<String> = filterIsInstance<CheckBoxFilterList>()
        .firstOrNull { it.name == name }
        ?.state
        ?.filter { it.state }
        ?.map { it.name }
        .orEmpty()

    fun build(preferences: SharedPreferences? = null): AnimeFilterList {
        val cachedGenres = preferences?.getString(PREF_GENRES, null)
            ?.split("\n")?.filter { it.isNotBlank() }
            ?: AnikageFiltersData.GENRES.toList()

        val cachedTags = preferences?.getString(PREF_TAGS, null)
            ?.split("\n")?.filter { it.isNotBlank() }
            ?: AnikageFiltersData.TAGS.toList()

        return AnimeFilterList(
            OriginFilter(),
            SeasonFilter(),
            ReleaseYearFilter(),
            SortByFilter(),
            AnimeFilter.Separator(),
            TypesFilter(),
            StatusFilter(),
            GenresFilter(cachedGenres),
            TagsFilter(cachedTags),
        )
    }

    data class FilterSearchParams(
        val origin: String = "ALL",
        val season: String = "ALL",
        val releaseYear: String = "ALL",
        val sortBy: String = "trending",
        val types: String = "ALL",
        val status: String = "ALL",
        val genres: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
    )

    fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            origin = filters.asQueryPart<OriginFilter>(),
            season = filters.asQueryPart<SeasonFilter>(),
            releaseYear = filters.asQueryPart<ReleaseYearFilter>(),
            sortBy = filters.asQueryPart<SortByFilter>(),
            types = filters.asQueryPart<TypesFilter>(),
            status = filters.asQueryPart<StatusFilter>(),
            genres = filters.parseSelected("Genres"),
            tags = filters.parseSelected("Tags / Tropes"),
        )
    }

    private object AnikageFiltersData {
        val ORIGIN = arrayOf(
            Pair("All", "ALL"),
            Pair("Japan", "JP"),
            Pair("Korea", "KR"),
            Pair("China", "CN"),
            Pair("Taiwan", "TW"),
        )

        val SEASONS = arrayOf(
            Pair("All", "ALL"),
            Pair("Winter", "WINTER"),
            Pair("Spring", "SPRING"),
            Pair("Summer", "SUMMER"),
            Pair("Fall", "FALL"),
        )

        private val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        val YEARS: Array<Pair<String, String>> = buildList {
            add("All" to "ALL")
            addAll(
                (currentYear + 1 downTo 1940).map {
                    it.toString() to it.toString()
                },
            )
        }.toTypedArray()

        val SORT_BY = arrayOf(
            Pair("Trending", "trending"),
            Pair("Popularity", "popularity"),
            Pair("Average Score", "score"),
            Pair("Favorites", "favourites"),
            Pair("Date Added", "updated"),
            Pair("Release Date", "year"),
            Pair("Title", "title"),
        )

        val TYPES = arrayOf(
            Pair("All", "ALL"),
            Pair("TV", "TV"),
            Pair("TV Short", "TV_SHORT"),
            Pair("Movie", "MOVIE"),
            Pair("Special", "SPECIAL"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Music", "MUSIC"),
        )

        val STATUS = arrayOf(
            Pair("All", "ALL"),
            Pair("Finished", "FINISHED"),
            Pair("Releasing", "RELEASING"),
            Pair("Not Yet Released", "NOT_YET_RELEASED"),
            Pair("Cancelled", "CANCELLED"),
        )

        val GENRES = arrayOf(
            "Action",
            "Adventure",
            "Comedy",
            "Drama",
            "Ecchi",
            "Fantasy",
            "Hentai",
            "Horror",
            "Mahou Shoujo",
            "Mecha",
            "Music",
            "Mystery",
            "Psychological",
            "Romance",
            "Sci-Fi",
            "Slice of Life",
            "Sports",
            "Supernatural",
            "Thriller",
        )

        val TAGS = arrayOf(
            "Alien",
            "Angels",
            "Anthropomorphic",
            "Assassins",
            "Body Swapping",
            "Butler",
            "Castelia",
            "CGI",
            "Cheating",
            "Chibi",
            "Chimera",
            "Chuunibyou",
            "Clone",
            "College",
            "Conspiracy",
            "Cosplay",
            "Crime",
            "Crossdressing",
            "Cyborg",
            "Cyberpunk",
            "Delinquents",
            "Demons",
            "Detective",
            "Dinosaurs",
            "Disability",
            "Dragons",
            "Drawing",
            "Dungeon",
            "Dystopian",
            "Economics",
            "Educational",
            "Elderly Protagonist",
            "Elf",
            "Ensemble Cast",
            "Environmental",
            "Episodic",
            "Espionage",
            "E-Sports",
            "Fairy",
            "Fairy Tale",
            "Family Life",
            "Fashion",
            "Female Protagonist",
            "Fishing",
            "Fitness",
            "Flash",
            "Food",
            "Foreign",
            "Found Family",
            "Fugitive",
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
            "Historical",
            "Ice Skating",
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
            "Maids",
            "Male Protagonist",
            "Martial Arts",
            "Memory Manipulation",
            "Meta",
            "Military",
            "Monster Girl",
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
            "Photography",
            "Pirates",
            "Poker",
            "Police",
            "Politics",
            "Post-Apocalyptic",
            "Primarily Adult Cast",
            "Primarily Female Cast",
            "Primarily Male Cast",
            "Puppetry",
            "Real Robot",
            "Rehabilitation",
            "Reincarnation",
            "Revenge",
            "Reverse Harem",
            "Robots",
            "Rugby",
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
            "Tennis",
            "Terrorism",
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
