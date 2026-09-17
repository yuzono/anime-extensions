package eu.kanade.tachiyomi.animeextension.es.tokianime18

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class AudioFilter :
    AnimeFilter.Select<String>(
        "Audio",
        arrayOf(
            "Todos",
            "Subtitulado",
            "DUB-EN",
            "DUB-ES",
            "RAW",
            "SUB-EN",
        ),
    ) {
    val selected get() = when (state) {
        1 -> "SUB"
        2 -> "DUB-EN"
        3 -> "DUB-ES"
        4 -> "RAW"
        5 -> "SUB-EN"
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
            "Popular",
            "Tendencia",
            "A-Z",
        ),
    ) {
    val selected get() = when (state) {
        1 -> "trending"
        2 -> "az"
        else -> "popular"
    }
}
