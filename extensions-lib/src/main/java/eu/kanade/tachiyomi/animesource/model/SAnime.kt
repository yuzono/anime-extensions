@file:Suppress("ktlint:standard:property-naming")

package eu.kanade.tachiyomi.animesource.model

import eu.kanade.tachiyomi.animesource.AnimeSource
import keiyoushi.annotation.AnimiruOnly

interface SAnime {

    var url: String

    var title: String

    var artist: String?

    var author: String?

    var description: String?

    /**
     * A string containing list of all genres separated with `", "`
     */
    var genre: String?

    /**
     * An "enum" value. Refer to the values in [SAnime.Companion].
     */
    var status: Int

    var thumbnail_url: String?

    var background_url: String?

    /**
     * Useful to exclude animes/movies that will always only have the same episode list
     * from the global updates.
     */
    var update_strategy: AnimeUpdateStrategy

    var fetch_type: FetchType

    var season_number: Double

    /**
     * Tells the app if it should call [AnimeSource.getAnimeDetails].
     */
    var initialized: Boolean

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        @AnimiruOnly
        const val UPCOMING = 7

        fun create(): SAnime = throw Exception("Stub!")
    }
}
