@file:Suppress("ktlint:standard:property-naming")

package eu.kanade.tachiyomi.animesource.model

interface SEpisode {

    var url: String

    var name: String

    var date_upload: Long

    var episode_number: Float

    var fillermark: Boolean

    var scanlator: String?

    var summary: String?

    var preview_url: String?

    companion object {
        fun create(): SEpisode = throw Exception("Stub!")
    }
}
