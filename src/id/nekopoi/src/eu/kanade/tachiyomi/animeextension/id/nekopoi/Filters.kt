package eu.kanade.tachiyomi.animeextension.id.nekopoi

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].second
    }

    class CategoryFilter : UriPartFilter("Kategori", FiltersData.CATEGORIES)

    class GenreFilter : UriPartFilter("Genre", FiltersData.GENRES)

    val FILTER_LIST
        get() = AnimeFilterList(
            AnimeFilter.Header("Catatan: Filter diabaikan jika menggunakan pencarian teks"),
            CategoryFilter(),
            GenreFilter(),
        )

    data class FilterSearchParams(
        val category: String = "",
        val genre: String = "",
    )

    fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        var category = ""
        var genre = ""

        for (filter in filters) {
            when (filter) {
                is CategoryFilter -> category = filter.toUriPart()
                is GenreFilter -> genre = filter.toUriPart()
                else -> {}
            }
        }

        return FilterSearchParams(
            category = category,
            genre = genre,
        )
    }

    private object FiltersData {
        val CATEGORIES = arrayOf(
            Pair("Semua Kategori", ""),
            Pair("Hentai", "hentai"),
            Pair("2D Animation", "2d-animation"),
            Pair("3D Hentai", "3d-hentai"),
            Pair("JAV", "jav"),
            Pair("JAV Cosplay", "jav-cosplay"),
        )

        val GENRES = arrayOf(
            Pair("Semua Genre", ""),
            Pair("Action", "action"),
            Pair("Ahegao", "ahegao"),
            Pair("Anal", "anal"),
            Pair("Armpit", "armpit"),
            Pair("BDSM", "bdsm"),
            Pair("Big Oppai", "big-oppai"),
            Pair("Blackmail", "blackmail"),
            Pair("Blonde", "blonde"),
            Pair("Blowjob", "blowjob"),
            Pair("Bondage", "bondage"),
            Pair("Cheating", "cheating"),
            Pair("Comedy", "comedy"),
            Pair("Creampie", "creampie"),
            Pair("Dark Skin", "dark-skin"),
            Pair("DILF", "dilf"),
            Pair("Elf", "elf"),
            Pair("Exhibitionist", "exhibitionist"),
            Pair("Fellatio", "fellatio"),
            Pair("Female Monster", "female-monster"),
            Pair("Femdom", "femdom"),
            Pair("Footjob", "footjob"),
            Pair("Forced", "forced"),
            Pair("Furry", "furry"),
            Pair("Futanari", "futanari"),
            Pair("Gangbang", "gangbang"),
            Pair("Gore", "gore"),
            Pair("Gyaru", "gyaru"),
            Pair("Handjob", "handjob"),
            Pair("Harem", "harem"),
            Pair("Horror", "horror"),
            Pair("Housewife", "housewife"),
            Pair("Humilation", "humilation"),
            Pair("Humiliation", "humiliation"),
            Pair("Hypnotize", "hypnotize"),
            Pair("Incest", "incest"),
            Pair("Intercrural", "intercrural"),
            Pair("JAV", "jav"),
            Pair("Lactation", "lactation"),
            Pair("Loli", "loli"),
            Pair("Maid", "maid"),
            Pair("Male Monster", "male-monster"),
            Pair("Masturbation", "masturbation"),
            Pair("Megane", "megane"),
            Pair("MILF", "milf"),
            Pair("Mind Control", "mind-control"),
            Pair("Monster", "monster"),
            Pair("Netorare", "netorare"),
            Pair("Nipple Fuck", "nipple-fuck"),
            Pair("Nurse", "nurse"),
            Pair("Old man", "old-man"),
            Pair("Onee-san", "onee-san"),
            Pair("Oral", "oral"),
            Pair("Paihame", "paihame"),
            Pair("Paizuri", "paizuri"),
            Pair("Pantyhose", "pantyhose"),
            Pair("Pregnant", "pregnant"),
            Pair("Prostitution", "prostitution"),
            Pair("Rape", "rape"),
            Pair("Romance", "romance"),
            Pair("Saimin", "saimin"),
            Pair("Schoolgirl", "schoolgirl"),
            Pair("Semi-Hentai", "semi-hentai"),
            Pair("Sex Toys", "sex-toys"),
            Pair("Shibari", "shibari"),
            Pair("Shota", "shota"),
            Pair("Stocking", "stocking"),
            Pair("Succubus", "succubus"),
            Pair("Supranatural", "supranatural"),
            Pair("Swimsuit", "swimsuit"),
            Pair("Tentacles", "tentacles"),
            Pair("Threesome", "threesome"),
            Pair("Tsundere", "tsundere"),
            Pair("Ugly Bastard", "ugly-bastard"),
            Pair("Uncensored", "uncensored"),
            Pair("Vanilla", "vanilla"),
            Pair("Virgin", "virgin"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )
    }
}
