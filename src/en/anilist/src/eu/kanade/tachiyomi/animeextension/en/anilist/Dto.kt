package eu.kanade.tachiyomi.animeextension.en.anilist

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

@Serializable
class Mapping(
    @SerialName("mal_id") val malId: Int? = null,
    @SerialName("anilist_id") val anilistId: Int? = null,
    @SerialName("thetvdb_id") val thetvdbId: Int? = null,
)

@Serializable
class PagesResponse(
    val data: PagesData,
) {
    @Serializable
    class PagesData(
        @SerialName("Page") val page: PageObject,
    ) {
        @Serializable
        class PageObject(
            val pageInfo: PageInfoObject,
            val media: List<MediaObject>,
        ) {
            @Serializable
            class PageInfoObject(
                val hasNextPage: Boolean,
            )

            @Serializable
            class MediaObject(
                val id: Int,
                @SerialName("title")
                val animeTitle: TitleObject,
                val coverImage: CoverObject,
            ) {
                fun toSAnime(titlePref: String): SAnime = SAnime.create().apply {
                    title = when (titlePref) {
                        "romaji" -> animeTitle.romaji ?: animeTitle.english ?: animeTitle.native ?: ""
                        "english" -> animeTitle.english ?: animeTitle.romaji ?: animeTitle.native ?: ""
                        else -> animeTitle.native ?: animeTitle.romaji ?: animeTitle.english ?: ""
                    }
                    thumbnail_url = coverImage.extraLarge ?: coverImage.large ?: coverImage.medium ?: ""
                    url = id.toString()
                }

                @Serializable
                class TitleObject(
                    val romaji: String? = null,
                    val english: String? = null,
                    val native: String? = null,
                )
            }
        }
    }
}

@Serializable
class DetailsResponse(
    val data: DetailsData,
) {
    @Serializable
    class DetailsData(
        @SerialName("Media") val media: MediaObject,
    ) {
        @Serializable
        class MediaObject(
            val id: Int,
            @SerialName("title")
            val animeTitle: TitleObject,
            val coverImage: CoverObject,
            val description: String? = null,
            val season: String? = null,
            val seasonYear: Int? = null,
            val format: String? = null,
            val status: String? = null,
            val genres: List<String> = emptyList(),
            val studios: StudioObject? = null,
            val episodes: Int? = null,
        ) {
            fun toSAnime(titlePref: String): SAnime = SAnime.create().apply {
                url = id.toString()
                thumbnail_url = coverImage.extraLarge ?: coverImage.large ?: coverImage.medium ?: ""
                title = when (titlePref) {
                    "romaji" -> animeTitle.romaji ?: animeTitle.english ?: animeTitle.native ?: ""
                    "english" -> animeTitle.english ?: animeTitle.romaji ?: animeTitle.native ?: ""
                    else -> animeTitle.native ?: animeTitle.romaji ?: animeTitle.english ?: ""
                }

                description = buildString {
                    append(
                        this@MediaObject.description?.let {
                            Jsoup.parseBodyFragment(
                                it.replace("<br>\n", "br2n")
                                    .replace("<br>", "br2n")
                                    .replace("\n", "br2n"),
                            ).text().replace("br2n", "\n")
                        },
                    )
                    append("\n\n")
                    if (!(season == null && seasonYear == null)) {
                        append("Release: ${season ?: ""} ${seasonYear ?: ""}")
                    }
                    format?.let { append("\nType: $format") }
                    episodes?.let { append("\nTotal Episode Count: $episodes") }
                }.trim()

                status = when (this@MediaObject.status) {
                    "FINISHED" -> SAnime.COMPLETED
                    "RELEASING" -> SAnime.ONGOING
                    "CANCELLED" -> SAnime.CANCELLED
                    "HIATUS" -> SAnime.ON_HIATUS
                    else -> SAnime.UNKNOWN
                }

                genre = this@MediaObject.genres.joinToString(", ")

                author = studios?.let {
                    it.edges.firstOrNull { edge -> edge.isMain }?.node?.name
                        ?: it.edges.firstOrNull()?.node?.name
                }
            }

            @Serializable
            class TitleObject(
                val romaji: String? = null,
                val english: String? = null,
                val native: String? = null,
            )

            @Serializable
            class StudioObject(
                val edges: List<Studio>,
            ) {
                @Serializable
                class Studio(
                    val isMain: Boolean,
                    val node: NodeObject,
                ) {
                    @Serializable
                    class NodeObject(
                        val name: String,
                    )
                }
            }
        }
    }
}

@Serializable
class CoverObject(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
class AniListEpisodeResponse(
    val data: DataObject,
) {
    @Serializable
    class DataObject(
        @SerialName("Media") val media: MediaObject,
    ) {
        @Serializable
        class MediaObject(
            val episodes: Int? = null,
            val nextAiringEpisode: NextAiringObject? = null,
        ) {
            @Serializable
            class NextAiringObject(
                val episode: Int,
            )
        }
    }
}

@Serializable
class AnilistToMalResponse(
    val data: DataObject,
) {
    @Serializable
    class DataObject(
        @SerialName("Media") val media: MediaObject,
    ) {
        @Serializable
        class MediaObject(
            val id: Int,
            val status: String,
            val idMal: Int? = null,
        )
    }
}

@Serializable
class JikanAnimeDto(
    val data: JikanAnimeDataDto,
) {
    @Serializable
    class JikanAnimeDataDto(
        val aired: AiredDto,
    ) {
        @Serializable
        class AiredDto(
            val from: String,
        )
    }
}

@Serializable
class JikanEpisodesDto(
    val pagination: JikanPaginationDto,
    val data: List<JikanEpisodesDataDto>,
) {
    @Serializable
    class JikanPaginationDto(
        @SerialName("has_next_page") val hasNextPage: Boolean,
        @SerialName("last_visible_page") val lastPage: Int,
    )

    @Serializable
    class JikanEpisodesDataDto(
        @SerialName("mal_id") val number: Int,
        val title: String? = null,
        val aired: String? = null,
        val filler: Boolean,
    )
}

@Serializable
class MALPicturesDto(
    val data: List<MALCoverDto>? = null,
) {
    @Serializable
    class MALCoverDto(
        val jpg: MALJpgDto? = null,
    ) {
        @Serializable
        class MALJpgDto(
            @SerialName("image_url") val imageUrl: String? = null,
            @SerialName("small_image_url") val smallImageUrl: String? = null,
            @SerialName("large_image_url") val largeImageUrl: String? = null,
        )
    }
}

@Serializable
class FanartDto(
    val tvposter: List<ImageDto>? = null,
) {
    @Serializable
    class ImageDto(
        val url: String,
    )
}
