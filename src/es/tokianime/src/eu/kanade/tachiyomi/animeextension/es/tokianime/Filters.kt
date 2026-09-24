package eu.kanade.tachiyomi.animeextension.es.tokianime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class StatusFilter :
    AnimeFilter.Select<String>(
        "Estado",
        arrayOf(
            "Todos",
            "En emisión",
            "Finalizado",
        ),
    ) {
    val selected get() = when (state) {
        1 -> "RELEASING"
        2 -> "FINISHED"
        else -> "ALL"
    }
}

class FormatFilter :
    AnimeFilter.Select<String>(
        "Formato",
        arrayOf(
            "Todos",
            "TV",
            "Película",
            "OVA",
            "ONA",
        ),
    ) {
    val selected get() = when (state) {
        1 -> "TV"
        2 -> "MOVIE"
        3 -> "OVA"
        4 -> "ONA"
        else -> "ALL"
    }
}

class AudioFilter :
    AnimeFilter.Select<String>(
        "Audio",
        arrayOf(
            "Todos",
            "Español Latino",
            "Castellano",
            "Inglés",
            "Subtitulado",
        ),
    ) {
    val selected get() = when (state) {
        1 -> "LAT"
        2 -> "CAST"
        3 -> "EN"
        4 -> "SUB"
        else -> "ALL"
    }
}

class GenreFilter :
    AnimeFilter.Group<Genre>(
        "Géneros",
        listOf(
            Genre("Acción"),
            Genre("Aventura"),
            Genre("Comedia"),
            Genre("Drama"),
            Genre("Fantasía"),
            Genre("Romance"),
            Genre("Sci-Fi"),
            Genre("Sobrenatural"),
            Genre("Misterio"),
            Genre("Ecchi"),
            Genre("Mecha"),
            Genre("Psicológico"),
            Genre("Terror"),
            Genre("Crimen"),
            Genre("Deportes"),
        ),
    )

class Genre(name: String) : AnimeFilter.CheckBox(name)

class SortFilter :
    AnimeFilter.Select<String>(
        "Ordenar",
        arrayOf(
            "Popular",
            "Valorados",
            "Tendencia",
            "A-Z",
        ),
    ) {
    val selected get() = when (state) {
        1 -> "rated"
        2 -> "trending"
        3 -> "az"
        else -> "popular"
    }
}
