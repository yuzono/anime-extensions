package eu.kanade.tachiyomi.animeextension.en.hentaihaven

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

// ── Shared base ───────────────────────────────────────────────────────────────

open class QueryParam(
    name: String,
    val param: String,
    values: Array<String>,
) : AnimeFilter.Select<String>(name, values) {
    val selected get() = values[state]
}

// ── Sort ──────────────────────────────────────────────────────────────────────

class SortFilter :
    QueryParam(
        "Sort by",
        "m_orderby",
        arrayOf("Latest", "A-Z", "Rating", "Most Views", "New"),
    ) {
    val urlValue: String
        get() = when (state) {
            0 -> "latest"
            1 -> "alphabet"
            2 -> "rating"
            3 -> "views"
            4 -> "new-manga"
            else -> "latest"
        }
}

// ── Shared slug helper ────────────────────────────────────────────────────────

private fun toSlug(label: String): String = label.lowercase()
    .replace(Regex("[/,]"), "")
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')

// ── Genre (broad category) ────────────────────────────────────────────────────
class GenreFilter :
    AnimeFilter.Select<String>(
        "Genre",
        arrayOf(
            "Any",
            "3D Hentai",
            "Anal",
            "BBW",
            "BDSM",
            "Beastiality",
            "Ecchi",
            "FemBoy",
            "Femdom",
            "Furry",
            "Futanari",
            "Gender Bender Hentai",
            "Harem",
            "Hentai School",
            "Horror",
            "Incest Hentai",
            "Milf",
            "Monster",
            "Romance",
            "Softcore",
            "Teen Hentai",
            "Tentacle",
            "Tsundere",
            "Umemaro 3D",
            "Uncensored Hentai",
            "Yaoi",
            "Young Hentai",
            "Yuri",
        ),
    ) {
    fun browseUrl(baseUrl: String): String? {
        if (state == 0) return null
        return "$baseUrl/series/${toSlug(values[state])}/"
    }
}

// ── Tag (granular descriptor) ─────────────────────────────────────────────────
class TagFilter :
    AnimeFilter.Select<String>(
        "Tag",
        arrayOf(
            "Any",
            "2020",
            "3D",
            "3D Anime Porn",
            "3D Hentai",
            "3D Hentai Haven",
            "Ahegao",
            "Anal",
            "Anal Hentai",
            "Androids",
            "Anime",
            "Anime Hentai",
            "Anime Porn",
            "Big Boobs",
            "Big Tits Hentai",
            "Blow Job",
            "Censored",
            "Creampie",
            "Cum in Pussy",
            "e Hentai",
            "eHentai",
            "Free Hentai",
            "ge Hentai",
            "Gelbooru",
            "Hanime",
            "Hanime TV",
            "HD",
            "Hentai",
            "Hentai Anime",
            "Hentai Chan",
            "Hentai Haven",
            "Hentai Manga",
            "Hentai Porn",
            "Hentai Stream",
            "Hentai TV",
            "Hentai Vid",
            "Hentai Video",
            "Hentai Videos",
            "Masturbation",
            "MioHentai",
            "mp4Hentai",
            "Naughty Hentai",
            "nHentai",
            "oHentai",
            "Oral Sex",
            "Orgasm",
            "Rule 34",
            "Sexy",
            "Tits",
            "Watch Hentai",
            "xAnimePorn",
            "xHentai",
        ),
    ) {
    fun browseUrl(baseUrl: String): String? {
        if (state == 0) return null
        return "$baseUrl/tag/${toSlug(values[state])}/"
    }
}
