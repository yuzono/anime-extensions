package eu.kanade.tachiyomi.animeextension.es.tokianime18

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class AudioFilter :
    AnimeFilter.Select<String>(
        "Audio",
        arrayOf(
            "Todos",
            "Subtitulado",
            "Doblado",
            "Español Latino",
            "Castellano",
        ),
    ) {
    val selected get() = when (state) {
        1 -> "SUB"
        2 -> "DUB"
        3 -> "LAT"
        4 -> "CAST"
        else -> "ALL"
    }
}

class GenreFilter :
    AnimeFilter.Group<Genre>(
        "Géneros",
        listOf(
            Genre("Sin Censura"),
            Genre("Hentai"),
            Genre("Tetonas"),
            Genre("Harem"),
            Genre("Anal"),
            Genre("Escolares"),
            Genre("3D"),
            Genre("Uncensored"),
            Genre("Romance"),
            Genre("Milfs"),
            Genre("Bondage"),
            Genre("Yuri"),
            Genre("Incesto"),
            Genre("Ahegao"),
            Genre("Orgias"),
            Genre("Censurado"),
            Genre("Ninfomania"),
            Genre("Lolicon"),
            Genre("Netorare"),
            Genre("Tentaculos"),
            Genre("BDSM"),
            Genre("Hardcore"),
            Genre("Futanari"),
            Genre("Tsundere"),
            Genre("Gangbang"),
            Genre("Vanilla"),
            Genre("Fantasy"),
            Genre("Maids"),
            Genre("Hentai sin Censura"),
            Genre("Teacher"),
            Genre("Ecchi"),
            Genre("Femdom"),
            Genre("MILF"),
            Genre("NTR"),
            Genre("Chikan"),
            Genre("Yaoi"),
            Genre("Bukakke"),
        ),
    )

class Genre(name: String) : AnimeFilter.CheckBox(name)

class SortFilter :
    AnimeFilter.Select<String>(
        "Ordenar",
        arrayOf(
            "Tendencia",
            "Popular",
            "A-Z",
        ),
    ) {
    val selected get() = when (state) {
        1 -> "popular"
        2 -> "az"
        else -> "trending"
    }
}
