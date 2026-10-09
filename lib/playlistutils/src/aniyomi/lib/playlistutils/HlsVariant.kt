package aniyomi.lib.playlistutils

import eu.kanade.tachiyomi.animesource.model.Track
import keiyoushi.utils.UrlUtils
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A video variant (`#EXT-X-STREAM-INF`) of an HLS master playlist.
 *
 * @property url absolute URL of the variant's media playlist
 * @property attributes the variant's attribute list, such as `BANDWIDTH`, `RESOLUTION` or `CODECS`
 * @property audioTracks renditions of the AUDIO group the variant references
 * @property subtitleTracks renditions of the SUBTITLES group the variant references
 */
class HlsVariant(
    val url: String,
    val attributes: Map<String, String>,
    val audioTracks: List<Track>,
    val subtitleTracks: List<Track>,
)

/**
 * Parses the video variants of an HLS master playlist, or returns null for a media playlist.
 *
 * Audio-only variants are skipped. A variant only gets the renditions of the AUDIO/SUBTITLES group
 * it references; variants without a known group, and renditions of groups no variant references,
 * get every rendition of that type. Relative URIs are resolved against [playlistUrl], while
 * absolute URLs are kept byte-for-byte so signed URLs are never re-encoded. Audio renditions are
 * ordered by DEFAULT, then AUTOSELECT, keeping their playlist order within each priority.
 */
fun parseHlsMasterPlaylist(playlistUrl: String, masterPlaylist: String): List<HlsVariant>? {
    if (PLAYLIST_SEPARATOR !in masterPlaylist) return null

    val streams = masterPlaylist.substringAfter(PLAYLIST_SEPARATOR).split(PLAYLIST_SEPARATOR)
        .map { stream -> stream to stream.substringBefore('\n').hlsAttributes() }
    val renditions = masterPlaylist.lineSequence()
        .map(String::trim)
        .filter { it.startsWith(MEDIA_TAG) }
        .map { it.substringAfter(':').hlsAttributes() }
        .toList()
    val variantAttributes = streams.map { (_, attributes) -> attributes }
    val audioTracks = renditionTracks("AUDIO", renditions, variantAttributes, playlistUrl)
    val subtitleTracks = renditionTracks("SUBTITLES", renditions, variantAttributes, playlistUrl)

    return streams.mapNotNull { (stream, attributes) ->
        val codecs = attributes["CODECS"]
        if (!codecs.isNullOrBlank() && codecs.split(',').all { it.trim().substringBefore('.') in AUDIO_CODECS }) {
            return@mapNotNull null
        }
        val uri = stream.lineSequence().drop(1)
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
            ?: return@mapNotNull null
        HlsVariant(
            url = resolveHlsUri(uri, playlistUrl) ?: return@mapNotNull null,
            attributes = attributes,
            audioTracks = audioTracks(attributes["AUDIO"]),
            subtitleTracks = subtitleTracks(attributes["SUBTITLES"]),
        )
    }
}

/** Returns the tracks of each referenced group of [type], falling back to every rendition of that type. */
private fun renditionTracks(
    type: String,
    renditions: List<Map<String, String>>,
    variants: List<Map<String, String>>,
    playlistUrl: String,
): (String?) -> List<Track> {
    val ofType = renditions.filter { it["TYPE"] == type }.let { tracks ->
        if (type == "AUDIO") {
            tracks.sortedWith(compareByDescending<Map<String, String>> { it["DEFAULT"] == "YES" }.thenByDescending { it["AUTOSELECT"] == "YES" })
        } else {
            tracks
        }
    }
    val referenced = variants.mapNotNullTo(mutableSetOf()) { it[type] }
    fun List<Map<String, String>>.toTracks() = mapNotNull { attributes ->
        val uri = attributes["URI"] ?: return@mapNotNull null
        Track(
            resolveHlsUri(uri, playlistUrl) ?: return@mapNotNull null,
            attributes["NAME"] ?: attributes["LANGUAGE"] ?: attributes["GROUP-ID"] ?: type,
        )
    }.distinctBy(Track::url)

    val all = ofType.toTracks()
    val byGroup = ofType.mapNotNullTo(mutableSetOf()) { it["GROUP-ID"] }
        .filter { it in referenced }
        .associateWith { group ->
            (ofType.filter { it["GROUP-ID"] == group } + ofType.filter { it["GROUP-ID"] !in referenced }).toTracks()
        }
    return { group -> group?.let(byGroup::get) ?: all }
}

/** Parses an HLS attribute list, which may be in any order and contain quoted commas. */
private fun String.hlsAttributes(): Map<String, String> = HLS_ATTRIBUTE_REGEX.findAll(this).associate {
    it.groupValues[1] to (it.groups[2]?.value ?: it.groupValues[3].trim())
}

private fun resolveHlsUri(uri: String, playlistUrl: String): String? = when {
    uri.startsWith("https://", ignoreCase = true) || uri.startsWith("http://", ignoreCase = true) -> uri
    else -> playlistUrl.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: UrlUtils.fixUrl(uri, playlistUrl)
}

private const val PLAYLIST_SEPARATOR = "#EXT-X-STREAM-INF:"
private const val MEDIA_TAG = "#EXT-X-MEDIA:"
private val HLS_ATTRIBUTE_REGEX = Regex("""([A-Z0-9-]+)=(?:"([^"]*)"|([^,\r\n]*))""")
private val AUDIO_CODECS = setOf("mp4a", "opus", "vorbis", "ac-3", "ec-3", "flac", "alac")
