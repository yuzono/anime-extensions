package eu.kanade.tachiyomi.animeextension.all.yfantasy

import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

interface VideoEntry {
    val videoId: String

    val title: String

    /** Catalog tags, e.g. `Romance`, used to relate entries to each other. */
    val tags: List<String>

    /** Some entries have no title upstream, so the id, which is also the site slug, stands in. */
    val displayTitle: String
        get() = title.ifEmpty { videoId }

    /** Playable segments, ordered as the episode list is displayed. */
    val segments: List<SegmentDto>

    fun toSAnime(): SAnime
}

@Serializable
class AnimeDto(
    override val videoId: String,
    override val title: String,
    override val tags: List<String> = emptyList(),
    private val currentSegment: SegmentDto? = null,
    private val hiddenSegments: List<SegmentDto> = emptyList(),
) : VideoEntry {
    override val segments: List<SegmentDto>
        get() = (listOfNotNull(currentSegment) + hiddenSegments).sortedByDescending { it.segmentIndex }

    override fun toSAnime() = createSAnime(videoId, displayTitle, tags, currentSegment)
}

@Serializable
class AuthRequestDto(
    private val token: String,
)

@Serializable
class AuthDto(
    val sessionToken: String,
)

@Serializable
class ClaimRequestDto(
    private val videoId: String,
    private val segmentId: String,
)

@Serializable
class FeedDto(
    val items: List<FeedItemDto>,
)

@Serializable
class FeedItemDto(
    @SerialName("id") override val videoId: String,
    override val title: String,
    override val tags: List<String> = emptyList(),
    private val publicSegment: SegmentDto? = null,
) : VideoEntry {
    override val segments: List<SegmentDto>
        get() = listOfNotNull(publicSegment)

    override fun toSAnime() = createSAnime(videoId, displayTitle, tags, publicSegment)
}

@Serializable
class SegmentDto(
    private val id: String,
    val segmentIndex: Int,
    val imageUrl: String? = null,
    val speechLanguage: String? = null,
    private val signedUrl: String? = null,
) {
    /**
     * Directory of the stream files, e.g. `https://host/<uid>`.
     * Locked segments have no [signedUrl], so their id stands in as the episode url.
     */
    private val videoBaseUrl: String?
        get() = signedUrl?.toHttpUrlOrNull()?.let { url ->
            url.newBuilder()
                .removePathSegment(url.pathSegments.lastIndex)
                .query(null)
                .build()
                .toString()
        }

    fun toSEpisode(): SEpisode {
        val baseUrl = videoBaseUrl

        return SEpisode.create().apply {
            url = baseUrl ?: id
            name = "Episode ${segmentIndex + 1}" + if (baseUrl == null) " 🔒" else ""
            episode_number = (segmentIndex + 1).toFloat()
        }
    }
}

private fun createSAnime(
    videoId: String,
    title: String,
    tags: List<String>,
    segment: SegmentDto?,
) = SAnime.create().apply {
    this.url = videoId
    this.title = title
    thumbnail_url = segment?.imageUrl
    genre = tags.joinToString()
    description = segment?.speechLanguage?.let { "Speech language: ${it.toLanguageName()}" }
    initialized = true
    update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
}

/** Turns a language code such as `zh` into `Chinese`, keeping the code when it is unknown. */
private fun String.toLanguageName(): String = Locale.forLanguageTag(this).getDisplayLanguage(Locale.ENGLISH).ifEmpty { this }
