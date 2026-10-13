package aniyomi.lib.kodikextractor

import android.net.Uri
import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

/**
 * Extracts stream URLs from a [Kodik](https://kodikplayer.com) player page.
 *
 * Kodik does not expose its streams directly: the player page carries signed parameters
 * (`urlParams`) which have to be posted to `/ftor` on the player domain to obtain an
 * answer, and every returned `src` is a token that only the player's own JavaScript
 * can decode. So the work is:
 *
 * 1. read `urlParams` out of the page,
 * 2. post it to `/ftor` to get the per-quality tokens,
 * 3. run the page's own decoding function over each token with QuickJS,
 * 4. expand the result into a playlist.
 *
 * The DLE CMS sites that embed Kodik all duplicate that flow, hence this library.
 *
 * @param client the client to use; it is reused as-is so the caller's cookie jar,
 *   Cloudflare handling and rate limiting apply.
 * @param headers headers to send with the player requests.
 */
class KodikExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // The decoding script never changes for a given player build, so fetching and
    // scanning it once per URL is enough.
    private val decodeScriptCache = ConcurrentHashMap<String, String>()

    /**
     * @param playerUrl the Kodik player page, e.g. `https://kodikplayer.com/seria/1407443/abc/720p`.
     * @param prefix label prefixed to every video title, usually the dubbing name. It ends up
     *   in the title the user sees, so callers pass it in their source's own language
     *   (the library itself is language-agnostic).
     * @param qualities qualities to expand, as in `listOf("360", "480", "720", "1080")`.
     *   Anything the player has no rendition for is skipped.
     * @param probeHigherQuality Kodik's API reports at most 720p, but the CDN usually keeps a
     *   1080p rendition of the same file. When true, a missing 1080p entry is probed for.
     * @param subtitleList tracks to attach to every produced video.
     */
    suspend fun videosFromUrl(
        playerUrl: String,
        prefix: String = "",
        qualities: List<String> = listOf("360", "480", "720", "1080"),
        probeHigherQuality: Boolean = false,
        subtitleList: List<Track> = emptyList(),
    ): List<Video> {
        val label = prefix.takeIf(String::isNotBlank)?.let { "$it " }.orEmpty()

        val page = runCatching { fetchPlayerPage(playerUrl) }.getOrNull() ?: return emptyList()
        val formData = runCatching { extractFormData(page.html()) }.getOrNull() ?: return emptyList()
        if (formData.dSign.isEmpty()) return emptyList()

        val playerHost = playerUrl.toHttpUrlOrNull()?.host ?: return emptyList()
        val identity = resolveMediaIdentity(page, playerUrl) ?: return emptyList()

        val response = postForStreams(playerUrl, playerHost, formData, identity)
            ?: return emptyList()

        val scriptUrl = extractDecoderScriptUrl(page) ?: return emptyList()
        val decodeScript = decodeScriptCache.getOrPut(scriptUrl) {
            runCatching { client.get(scriptUrl, playerHeaders()).bodyString() }.getOrNull().orEmpty()
        }
        if (decodeScript.isEmpty()) return emptyList()

        val streamHeaders = streamHeaders(playerHost)

        return QuickJs.create().use { qjs ->
            val encodeScript = resolveDecodeFunction(decodeScript, response, qjs) ?: return emptyList()

            qualities.flatMap { quality ->
                val token = response.tokenFor(quality) ?: return@flatMap emptyList()
                val streamUrl = runCatching {
                    Base64.decode(qjs.evaluate("t='$token'; $encodeScript").toString(), Base64.DEFAULT)
                        .toString(Charsets.UTF_8)
                }.getOrNull()?.fixProtocol() ?: return@flatMap emptyList()

                // The trailing "p" matters: callers pick their preferred quality by parsing it
                // back out of the title, so "(720 Kodik)" would be invisible to them.
                val dashTitle = "$label($quality" + "p Kodik - %s)"
                val plainTitle = "$label($quality" + "p Kodik)"

                if (streamUrl.endsWith(".mpd")) {
                    playlistUtils.extractFromDash(
                        streamUrl,
                        { dashTitle.replace("%s", it) },
                        streamHeaders,
                        streamHeaders,
                        subtitleList = subtitleList,
                    )
                } else {
                    buildList {
                        if (probeHigherQuality && quality == "720" && response.full.isEmpty()) {
                            val higher = streamUrl.replace("/720.mp4", "/1080.mp4")
                            if (higher != streamUrl && isAvailable(higher, streamHeaders)) {
                                add(
                                    Video(
                                        higher,
                                        "$label(1080p Kodik)",
                                        higher,
                                        headers = streamHeaders,
                                        subtitleTracks = subtitleList,
                                    ),
                                )
                            }
                        }
                        add(
                            Video(
                                streamUrl,
                                plainTitle,
                                streamUrl,
                                headers = streamHeaders,
                                subtitleTracks = subtitleList,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Kodik pages contain a self-closing `<script .../>` inside an inline `<svg>`. Browsers
     * parse that as an empty element (SVG foreign-content rules), but Jsoup treats it as an
     * opening `<script>` tag and swallows the rest of the page as raw script text, which
     * hides the parameters we need. Balance such tags before parsing.
     */
    private suspend fun fetchPlayerPage(url: String): Document {
        val body = client.get(url, playerHeaders()).bodyString()
        return Jsoup.parse(body.replace(SELF_CLOSING_SCRIPT_REGEX, "<script$1></script>"), url)
    }

    private suspend fun playerHeaders(): Headers = headers.newBuilder()
        .set("Referer", headers["Referer"] ?: "https://kodikplayer.com/")
        .build()

    private fun streamHeaders(playerHost: String): Headers = headers.newBuilder()
        .set("Referer", "https://$playerHost/")
        .set("Origin", "https://$playerHost")
        .build()

    private fun String?.fixProtocol(): String? = when {
        isNullOrBlank() -> null
        startsWith("//") -> "https:$this"
        startsWith("http") -> this
        else -> null
    }

    // `urlParams` is a JSON blob assigned to a JS variable, quoted with either quote style.
    private fun extractFormData(pageHtml: String): KodikFormData? {
        val raw = URL_PARAMS_SINGLE_QUOTED_REGEX.find(pageHtml)?.groupValues?.get(1)
            ?: URL_PARAMS_DOUBLE_QUOTED_REGEX.find(pageHtml)?.groupValues?.get(1)
            ?: return null
        return runCatching { raw.parseAs<KodikFormData>() }.getOrNull()
    }

    /**
     * The media identity (type/id/hash) is needed for the `/ftor` call. Newer player
     * builds publish it in a `vInfo` object, older ones only carry it in the URL path.
     */
    private fun resolveMediaIdentity(page: Document, playerUrl: String): Triple<String, String, String>? {
        for (script in page.select("script").map { it.data() }) {
            val type = VIDEO_TYPE_REGEX.find(script)?.groupValues?.get(1) ?: continue
            val id = VIDEO_ID_REGEX.find(script)?.groupValues?.get(1) ?: continue
            val hash = VIDEO_HASH_REGEX.find(script)?.groupValues?.get(1) ?: continue
            return Triple(type, id, hash)
        }

        // Fallback to the player URL path: https://{host}/{type}/{id}/{hash}/720p
        val parts = playerUrl.substringAfter("://").substringBefore('?').split('/')
        val type = parts.getOrNull(1)
        val id = parts.getOrNull(2)
        val hash = parts.getOrNull(3)
        return if (type != null && id != null && hash != null) Triple(type, id, hash) else null
    }

    private suspend fun postForStreams(
        playerUrl: String,
        playerHost: String,
        formData: KodikFormData,
        identity: Triple<String, String, String>,
    ): KodikVideoQuality? {
        val body = FormBody.Builder()
            .add("d", formData.d)
            .add("d_sign", Uri.decode(formData.dSign))
            .add("pd", formData.pd)
            .add("pd_sign", Uri.decode(formData.pdSign))
            .add("ref", Uri.decode(formData.ref))
            .add("ref_sign", Uri.decode(formData.refSign))
            .add("type", identity.first)
            .add("id", identity.second)
            .add("hash", identity.third)
            .add("bad_user", "true")
            .add("cdn_is_working", "true")
            .build()

        val request = Request.Builder()
            .url("https://$playerHost/ftor")
            .post(body)
            .headers(
                headers.newBuilder()
                    .set("Referer", playerUrl)
                    .set("Origin", "https://$playerHost")
                    .build(),
            )
            .build()

        return runCatching { client.newCall(request).awaitSuccess().parseAs<KodikData>() }
            .getOrNull()
            ?.links
    }

    private fun KodikVideoQuality.tokenFor(quality: String): String? = when (quality) {
        "360" -> ugly.firstOrNull()?.src
        "480" -> bad.firstOrNull()?.src
        "720" -> good.firstOrNull()?.src
        "1080" -> full.firstOrNull()?.src
        else -> null
    }

    private fun extractDecoderScriptUrl(page: Document): String? = (
        page.selectFirst("script[src*=app.serial]")
            ?: page.selectFirst("script[src*=app.video]")
            ?: page.selectFirst("script[src*=player_single]")
            ?: page.selectFirst("script[src*=player]")
            ?: page.selectFirst("script[src*=app]")
        )?.attr("abs:src")

    /**
     * Picks the decode function out of the player's script.
     *
     * A player script can contain more than one `atob(` call, and only one of them
     * introduces the self-contained decoder. Rather than guessing which one it is — first,
     * last, whichever held until now — every candidate is tried against a real token and
     * kept only if it decodes to something that looks like a stream URL.
     */
    private fun resolveDecodeFunction(jsScript: String, quality: KodikVideoQuality, qjs: QuickJs): String? {
        val probe = quality.good.firstOrNull()?.src
            ?: quality.bad.firstOrNull()?.src
            ?: quality.ugly.firstOrNull()?.src
            ?: quality.full.firstOrNull()?.src
            ?: return null

        for (match in ATOB_REGEX.findAll(jsScript)) {
            val candidate = extractEncodeFunction(jsScript, match.range.last) ?: continue
            val decoded = runCatching { qjs.evaluate("t='$probe'; $candidate").toString() }.getOrNull() ?: continue
            val decodedUrl = runCatching {
                Base64.decode(decoded, Base64.DEFAULT).toString(Charsets.UTF_8)
            }.getOrNull()
            if (decodedUrl != null && decodedUrl.fixProtocol() != null) return candidate
        }
        return null
    }

    /**
     * Cuts a self-contained expression out of the player's script: everything from [start]
     * up to the point where the bracket opened there is balanced again.
     */
    private fun extractEncodeFunction(jsScript: String, start: Int): String? {
        val function = StringBuilder("(")
        val opened = ArrayDeque<Char>()
        opened.addFirst('(')
        for (i in start until jsScript.length) {
            val char = jsScript[i]
            when (char) {
                '(', '{' -> opened.addFirst(char)
                ')', '}' -> if (opened.isNotEmpty()) opened.removeFirst()
            }
            function.append(char)
            if (opened.isEmpty()) break
        }
        return function.takeIf { it.length > 1 }?.toString()
    }

    private suspend fun isAvailable(url: String, headers: Headers): Boolean = runCatching {
        client.get(url, headers).use { it.isSuccessful }
    }.getOrDefault(false)

    companion object {
        private val ATOB_REGEX = Regex("""atob\([^"]""")
        private val SELF_CLOSING_SCRIPT_REGEX = Regex("""<script([^>]*)/>""")
        private val URL_PARAMS_SINGLE_QUOTED_REGEX = Regex("""urlParams\s*=\s*'([^']+)'""")
        private val URL_PARAMS_DOUBLE_QUOTED_REGEX = Regex("""urlParams\s*=\s*"([^"]+)"""")
        private val VIDEO_TYPE_REGEX = Regex("""\.type\s*=\s*['"]([^'"]+)['"]""")
        private val VIDEO_HASH_REGEX = Regex("""\.hash\s*=\s*['"]([^'"]+)['"]""")
        private val VIDEO_ID_REGEX = Regex("""\.id\s*=\s*['"]?([A-Za-z0-9]+)['"]?""")
    }
}
