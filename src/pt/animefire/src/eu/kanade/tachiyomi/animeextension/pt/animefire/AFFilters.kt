package eu.kanade.tachiyomi.animeextension.pt.animefire

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AFFilters {
    class FormatFilter : AnimeFilter.Select<String>("Formato", arrayOf("Todos", "Filmes"))

    class GenreFilter : AnimeFilter.Select<String>("Gênero", GENRES) {
        fun value() = if (state == 0) "" else GENRES[state]
    }

    class AudioFilter : AnimeFilter.Select<String>("Áudio", arrayOf("Todos", "Dublado", "Legendado")) {
        fun value() = arrayOf("", "dublado", "legendado")[state]
    }

    val filterList get() =
        AnimeFilterList(
            AnimeFilter.Header("Os filtros são ignorados durante a pesquisa."),
            FormatFilter(),
            GenreFilter(),
            AudioFilter(),
        )

    private val GENRES =
        arrayOf(
            "Todos",
            "Ação",
            "Aventura",
            "Comédia",
            "Drama",
            "Ecchi",
            "Esporte",
            "Fantasia",
            "Ficção científica",
            "Harém",
            "Isekai",
            "Mecha",
            "Mistério",
            "Musical",
            "Romance",
            "Seinen",
            "Shoujo",
            "Shounen",
            "Slice of life",
            "Sobrenatural",
            "Suspense",
            "Terror",
            "Vida escolar",
            "Yaoi",
            "Yuri",
        )
}
