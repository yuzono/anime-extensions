package eu.kanade.tachiyomi.animeextension.en.anilist

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
class TitleObject(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
) {
    fun getTitle(titlePref: String): String? {
        val title = when (titlePref) {
            "english" -> english ?: romaji ?: native
            "native" -> native ?: romaji ?: english
            else -> romaji ?: english ?: native
        }
        return title?.takeIf { it.isNotBlank() }
    }
}

@Serializable
class CoverObject(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
) {
    val bestCoverUrl: String?
        get() = extraLarge ?: large ?: medium
}

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
                fun toSAnimeOrNull(titlePref: String): SAnime? {
                    val resolvedTitle = animeTitle.getTitle(titlePref) ?: return null
                    return SAnime.create().apply {
                        url = id.toString()
                        title = resolvedTitle
                        thumbnail_url = coverImage.bestCoverUrl
                    }
                }
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
            fun toSAnime(titlePref: String): SAnime {
                val resolvedTitle = animeTitle.getTitle(titlePref)
                    ?: throw IllegalStateException("Anime $id is missing title")
                return SAnime.create().apply {
                    url = id.toString()
                    thumbnail_url = coverImage.bestCoverUrl
                    title = resolvedTitle

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
                        id?.let { append("\n[AniList](https://anilist.co/anime/$id)") }
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
            }

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
class PersonalListResponse(
    val data: PersonalListData,
) {
    @Serializable
    class PersonalListData(
        @SerialName("Page") val page: PersonalListPage,
    ) {
        @Serializable
        class PersonalListPage(
            val pageInfo: PageInfoObject,
            val mediaList: List<PersonalListEntry> = emptyList(),
        ) {
            @Serializable
            class PageInfoObject(
                val hasNextPage: Boolean,
            )

            @Serializable
            class PersonalListEntry(
                val media: PersonalListMedia? = null,
            ) {
                @Serializable
                class PersonalListMedia(
                    val id: Int,
                    val isAdult: Boolean = false,
                    @SerialName("title")
                    val animeTitle: TitleObject,
                    val coverImage: CoverObject,
                ) {
                    fun toSAnimeOrNull(titlePref: String): SAnime? {
                        val resolvedTitle = animeTitle.getTitle(titlePref) ?: return null
                        return SAnime.create().apply {
                            url = id.toString()
                            title = resolvedTitle
                            thumbnail_url = coverImage.bestCoverUrl
                        }
                    }
                }
            }
        }
    }
}

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
            val status: String? = null,
            val idMal: Int? = null,
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
class JikanAnimeDto(
    val data: JikanAnimeDataDto,
) {
    @Serializable
    class JikanAnimeDataDto(
        val aired: AiredDto,
        val synopsis: String? = null,
        val images: MALPicturesDto.MALCoverDto? = null,
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
        val filler: Boolean = false,
        val synopsis: String? = null,
        val images: JikanImagesDto? = null,
    ) {
        @Serializable
        class JikanImagesDto(
            val jpg: JikanJpgDto? = null,
        ) {
            @Serializable
            class JikanJpgDto(
                @SerialName("image_url") val imageUrl: String? = null,
            )
        }
    }
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
    val movieposter: List<ImageDto>? = null,
) {
    @Serializable
    class ImageDto(
        val url: String,
    )
}

@Serializable
class ViewerResponse(
    val data: ViewerData,
) {
    @Serializable
    class ViewerData(
        @SerialName("Viewer") val viewer: ViewerObject? = null,
    ) {
        @Serializable
        class ViewerObject(
            val name: String? = null,
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class SortVariables(
    val page: Int,
    val perPage: Int,
    val sort: List<String>,
    @EncodeDefault val type: String = "ANIME",
    val status: String? = null,
    val isAdult: Boolean? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class SearchVariables(
    val page: Int,
    val perPage: Int,
    val sort: List<String>? = null,
    @EncodeDefault val type: String = "ANIME",
    val search: String? = null,
    val genres: List<String>? = null,
    val format: List<String>? = null,
    val year: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val status: String? = null,
    val countryOfOrigin: String? = null,
    val isAdult: Boolean? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class PersonalListVariables(
    val userName: String,
    @EncodeDefault val type: String = "ANIME",
    val status: String? = null,
    val page: Int,
    val perPage: Int,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class MediaVariables(
    val id: Int,
    @EncodeDefault val type: String = "ANIME",
)
