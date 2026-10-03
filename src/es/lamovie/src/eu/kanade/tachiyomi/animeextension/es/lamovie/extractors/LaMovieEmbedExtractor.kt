package eu.kanade.tachiyomi.animeextension.es.lamovie.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.es.lamovie.EmbedConfigDto
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class LaMovieEmbedExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String, prefix: String): List<Video> {
        val parsedUrl = url.toHttpUrlOrNull()
        val origin = parsedUrl?.let { "${it.scheme}://${it.host}" } ?: DEFAULT_ORIGIN
        val referer = "$origin/"

        val embedHeaders = headers.newBuilder().apply {
            set("Origin", origin)
            set("Referer", referer)
        }.build()

        val body = client.newCall(GET(url, embedHeaders)).awaitSuccess().bodyString()

        var playlistUrl: String? = null
        val subtitleAccumulator = linkedSetOf<Pair<String, String>>()

        fun addSubtitle(label: String?, rawUrl: String) {
            val resolvedUrl = rawUrl.unescapeUrl()
            if (resolvedUrl.isBlank()) return
            val resolvedLabel = label?.takeIf(String::isNotBlank) ?: "Subtitle"
            subtitleAccumulator.add(resolvedLabel to resolvedUrl)
        }

        CONFIG_REGEX.find(body)?.groupValues?.getOrNull(1)?.let { configText ->
            val config = runCatching { configText.parseAs<EmbedConfigDto>() }.getOrNull()
            config?.file?.takeIf(String::isNotBlank)?.let {
                playlistUrl = it.unescapeUrl()
            }
            config?.subtitle?.let { subtitleRaw ->
                SUBTITLE_REGEX.findAll(subtitleRaw).forEach { match ->
                    addSubtitle(match.groupValues[1], match.groupValues[2])
                }
            }
        }

        val scriptUnpacked = SCRIPT_REGEX.find(body)?.value?.let { script ->
            JsUnpacker.unpackAndCombine(script) ?: script
        }

        if (playlistUrl.isNullOrBlank()) {
            playlistUrl = scriptUnpacked?.let { unpacked ->
                M3U8_REGEX.find(unpacked)?.value?.unescapeUrl()
            }
        }

        scriptUnpacked?.let { unpacked ->
            SUBTITLE_REGEX.findAll(unpacked).forEach { match ->
                addSubtitle(match.groupValues[1], match.groupValues[2])
            }
        }

        val subtitleList = playlistUtils.fixSubtitles(
            subtitleAccumulator.map { (label, subUrl) -> Track(subUrl, label) },
        )

        val resolvedPlaylistUrl = playlistUrl ?: return emptyList()

        val videoNameGen: (String) -> String = { quality ->
            val label = when {
                quality.equals("Video", ignoreCase = true) || quality.isBlank() -> "HLS"
                quality.all(Char::isDigit) -> "${quality}p"
                else -> quality
            }
            if (prefix.isBlank()) label else "$prefix - $label"
        }

        return playlistUtils.extractFromHls(
            playlistUrl = resolvedPlaylistUrl,
            referer = referer,
            videoNameGen = videoNameGen,
            subtitleList = subtitleList,
        )
    }

    private fun String.unescapeUrl(): String = replace("\\/", "/").replace("&amp;", "&")

    companion object {
        private const val DEFAULT_ORIGIN = "https://lamovie.la"

        private val CONFIG_REGEX = Regex(
            pattern = """<script\s+id=['"]config['"][^>]*>(\{[\s\S]*?\})</script>""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val SCRIPT_REGEX = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?\.split('\|')\)\)""")
        private val M3U8_REGEX = Regex("""https?://[^\s'"]+\.m3u8[^\s'"]*""")
        private val SUBTITLE_REGEX = Regex("""\[(.+?)](https?://[^\s'"]+)""")
    }
}
