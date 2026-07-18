package eu.kanade.tachiyomi.animeextension.en.av1encodes

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

internal val SORT_VALUES = arrayOf("", "a-z", "z-a", "episodes")
internal val TYPE_VALUES = arrayOf("", "sub", "dual")
internal val GENRE_VALUES = arrayOf("", "action", "adventure", "comedy", "drama", "fantasy", "horror", "romance", "sci-fi")

internal class SortFilter :
    AnimeFilter.Select<String>(
        "Sort By",
        arrayOf("Latest Added", "A–Z", "Z–A", "Episode Count"),
    )

internal class TypeFilter :
    AnimeFilter.Select<String>(
        "Audio Type (overrides Sort)",
        arrayOf("All", "Sub only (Airing)", "Dual audio (Airing)"),
    )

internal class GenreFilter :
    AnimeFilter.Select<String>(
        "Genre",
        arrayOf(
            "All",
            "Action",
            "Adventure",
            "Comedy",
            "Drama",
            "Fantasy",
            "Horror",
            "Romance",
            "Sci-Fi",
        ),
    )
