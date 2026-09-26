package eu.kanade.tachiyomi.animeextension.en.hentaihaven

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

open class OptionFilter(name: String, private val options: List<Pair<String, String>>) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val value get() = options[state].second
}

class GenreFilter(options: List<Pair<String, String>>) : OptionFilter("Genre", options)

class ReleaseFilter(options: List<Pair<String, String>>) : OptionFilter("Release year", options)

class AuthorFilter(options: List<Pair<String, String>>) : OptionFilter("Author", options)

class SortFilter : AnimeFilter.Select<String>("Sort by", SORTS.map { it.first }.toTypedArray()) {
    val orderby get() = SORTS[state].second
    val order get() = SORTS[state].third

    private companion object {
        val SORTS = listOf(
            Triple("Newest", "date", "desc"),
            Triple("Updated", "modified", "desc"),
            Triple("A-Z", "title", "asc"),
        )
    }
}
