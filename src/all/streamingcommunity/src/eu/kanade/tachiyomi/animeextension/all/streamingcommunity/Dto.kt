package eu.kanade.tachiyomi.animeextension.all.streamingcommunity

import aniyomi.lib.i18n.Intl
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class ShowsResponse(
    val props: PropObject,
    val version: String, // x-inertia-version
)

@Suppress("PropertyName")
@Serializable
data class PropObject(
    val titles: List<TitleObject>,
    val scws_url: String?, // https://vixcloud.co (not available in API call)
    val cdn_url: String?, // https://cdn.streamingunity.to (not available in API call)
)

@Serializable
data class TitleObject(
    val id: Int,
    val slug: String,
    val name: String,
    val images: List<ImageObject>,
) {
    fun toSAnime(imageCdn: String) = SAnime.create().apply {
        title = name
        url = "$id-$slug"
        thumbnail_url = images.firstOrNull {
            it.type == "poster"
        }?.let {
            imageCdn + it.filename
        } ?: images.firstOrNull {
            it.type == "cover"
        }?.let {
            imageCdn + it.filename
        } ?: images.firstOrNull {
            it.type == "cover_mobile"
        }?.let {
            imageCdn + it.filename
        } ?: images.firstOrNull {
            it.type == "background"
        }?.let {
            imageCdn + it.filename
        }
    }
}

@Serializable
data class ImageObject(
    val filename: String,
    val type: String, // poster, cover, background, logo, cover_mobile
    val lang: String?, // "en", "it"
)

@Suppress("PropertyName")
@Serializable
data class SingleShowResponse(
    val props: SingleShowObject,
    val version: String? = null, // x-inertia-version
) {
    @Serializable
    data class SingleShowObject(
        val title: ShowObject? = null,
        val loadedSeason: LoadedSeasonObject? = null,
        val sliders: List<RelatedObject>?,
    ) {
        @Serializable
        data class ShowObject(
            val id: Int,
            val name: String,
            val original_name: String,
            val type: String,
            val plot: String? = null,
            val quality: String, // HD
            val status: String? = null,
            val runtime: Int?,
            val score: String?, // 7.7
            val tmdb_id: Int?,
            val imdb_id: String?, // tt20969586
            val release_date: String?, // "2018-01-07",
            val last_air_date: String?, // "2025-01-01"
            val age: Int?, // 16
            val seasons_count: Int, // 0 or 1,2,3...
            val seasons: List<SeasonObject>, // could be empty
            val trailers: List<TrailerObject>,
            val genres: List<GenreObject>? = null,
            val main_actors: List<ActorObject>,
            val main_directors: List<ActorObject>,
            val preview: PreviewObject?,
            val images: List<ImageObject>,
            val keywords: List<KeywordObject>,
            val created_at: String?, // "2023-08-21T19:51:07.000000Z",
            val updated_at: String?, // "2025-05-12T12:33:33.000000Z"
        ) {
            @Serializable
            data class SeasonObject(
                val id: Int,
                val number: Int,
                val name: String?, // Most likely `null` all the time
                val plot: String?, // Some has season plot
                val release_date: String?, // "2025-01-01"
                val episodes_count: Int,
            )

            @Serializable
            data class GenreObject(
                val name: String,
            )

            @Serializable
            data class TrailerObject(
                @SerialName("name")
                private val name: String?, // "Teaser Trailer"
                val is_hd: Int, // 1
                val youtube_id: String, // "0IqOJgtS5nw"
            ) {
                val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "Trailer"
            }

            @Serializable
            data class ActorObject(
                val name: String,
            )

            @Serializable
            data class PreviewObject(
                val video_id: Int, // 262817
                val is_viewable: Int, // 1
                val filename: String, // "preview-thunderbolts-1727430977.mp4"
                val embed_url: String, // "https://vixcloud.co/embed/262817?token=321fd9c4f94fcd28b522d1f3ba2a8d77&expires=1752778149&canPlayFHD=1&canBypassAds=1&nogui=1&zf=25"
            )

            @Serializable
            data class KeywordObject(
                val name: String,
            )

            fun toSAnimeUpdate(intl: Intl) = SAnime.create().apply {
                val parsedStatus = StreamingCommunity.parseStatus(this@ShowObject.status)

                val desc = StringBuilder().apply {
                    append(fancyScore)
                    plot?.let { append("$it\n\n") }
                    append(intl["original_name"]).append(": $original_name")
                    append("\n").append(intl["quality"]).append(": $quality")
                    runtime?.let { append(" - ").append(intl["run_time"]).append(": ${it}m") }
                    release_date?.let { append("\n").append(intl["release_date"]).append(": $it") }
                    if (parsedStatus == SAnime.UNKNOWN) {
                        append("\n").append(intl["status"]).append(": ${this@ShowObject.status}")
                    }
                    age?.let { append("\n").append(intl["rating"]).append(": $it+") }
                    main_actors.joinToString { it.name }
                        .let { if (it.isNotBlank()) append("\n\n").append(intl["cast"]).append(": $it\n") }
                    imdb_id?.let { append("\n[IMDB](https://www.imdb.com/title/$it)") }
                    tmdb_id?.let { append("\n[TMDB](https://www.themoviedb.org/$type/$it)") }
                    trailers.forEach { append("\n[${it.displayName}](https://www.youtube.com/watch?v=${it.youtube_id})") }
                }.toString()

                description = desc
                status = parsedStatus
                genre = genres?.joinToString(", ") { it.name }
                author = main_directors.joinToString { it.name }
            }

            private val fancyScore: String =
                score?.toFloatOrNull()?.div(2f)
                    ?.roundToInt()
                    ?.let {
                        buildString {
                            append("★".repeat(it))
                            if (it < 5) append("☆".repeat(5 - it))
                            append(" $score\n")
                        }
                    } ?: ""
        }

        @Serializable
        data class RelatedObject(
            val name: String, // "genre", "related"
            val label: String, // "Related", "Movies - Action", "Movies - Adventure"
            val titles: List<TitleObject>,
        )

        @Serializable
        data class LoadedSeasonObject(
            val id: Int,
            val number: Int,
            val name: String?, // Most likely `null` all the time
            val plot: String?, // Some has season plot
            val release_date: String?, // "2025-01-01"
            val episodes: List<EpisodeObject>,
        ) {
            @Serializable
            data class EpisodeObject(
                val id: Int,
                val number: Int,
                val name: String,
                val plot: String?,
                val duration: Int?, // 55
                val created_at: String?, // "2023-08-21T19:51:07.000000Z",
                val updated_at: String?, // "2025-05-12T12:33:33.000000Z"
                val images: List<ImageObject>,
            )
        }
    }
}
