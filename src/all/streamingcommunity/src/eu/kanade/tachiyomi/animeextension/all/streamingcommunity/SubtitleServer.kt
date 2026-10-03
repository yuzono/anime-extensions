package eu.kanade.tachiyomi.animeextension.all.streamingcommunity

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Loopback proxy that hands the player one ready-to-read `.vtt` URL per
 * subtitle track.
 *
 * VixCloud advertises subtitles in the master playlist as HLS *subtitle
 * playlists* (`/playlist/<id>?type=subtitle&rendition=<n>-<lang>&token=...`),
 * not as VTT files. Handing those straight to the player costs two sequential
 * round trips per track - fetch the playlist, then fetch the single VTT
 * segment it points at - against a token-gated CDN. A popular title carries
 * ~38 renditions, so opening one stalled the track list behind ~76 requests.
 *
 * This collapses the indirection: each track is registered once and served
 * from `127.0.0.1` as a plain `.vtt`, and [warmUp] resolves the playlist hop
 * and caches the bytes on a small pool while the video itself is still
 * starting. By the time the player asks for a track it is normally already in
 * memory, and anything not yet warm resolves on demand instead of failing.
 */
class SubtitleServer(private val client: OkHttpClient) : NanoHTTPD(LOOPBACK_HOSTNAME, 0) {

    @Suppress("OVERRIDE_DEPRECATION")
    val port: Int
        get() = super.getListeningPort()

    private class Entry(val id: String, val url: String, val headers: Headers, val registeredAt: Long) {
        @Volatile
        var body: ByteArray? = null
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val idsByUrl = HashMap<String, String>()
    private val counter = AtomicLong()

    private val warmUpPool by lazy {
        Executors.newFixedThreadPool(WARM_UP_THREADS) { runnable ->
            Thread(runnable, "streamingunity-subs").apply { isDaemon = true }
        }
    }

    init {
        start(SOCKET_READ_TIMEOUT, true)
    }

    /**
     * Registers [tracks] and returns their loopback equivalents, preserving
     * order and language labels. Duplicate upstream URLs collapse onto a
     * single entry, so mirrors that advertise the same rendition list do not
     * multiply the track count.
     */
    fun proxy(tracks: List<Track>, headers: Headers): List<Track> {
        val now = System.currentTimeMillis()
        val fresh = mutableListOf<Entry>()

        val proxied = synchronized(entries) {
            evict(now)
            tracks.map { track ->
                val entry = idsByUrl[track.url]?.let(entries::get) ?: Entry(
                    id = counter.incrementAndGet().toString(Character.MAX_RADIX),
                    url = track.url,
                    headers = headers,
                    registeredAt = now,
                ).also {
                    entries[it.id] = it
                    idsByUrl[it.url] = it.id
                    fresh.add(it)
                }
                Track("http://$LOOPBACK_HOSTNAME:$port/sub/${entry.id}.vtt", track.lang)
            }
        }

        fresh.forEach(::warmUp)
        return proxied
    }

    /** Drops entries whose signed upstream URL has aged out, then trims to [MAX_ENTRIES]. */
    private fun evict(now: Long) {
        entries.values
            .filter { now - it.registeredAt >= ENTRY_TTL_MS }
            .map(Entry::id)
            .forEach(::remove)

        while (entries.size >= MAX_ENTRIES) {
            remove(entries.keys.first())
        }
    }

    private fun remove(id: String) {
        entries.remove(id)?.let { idsByUrl.remove(it.url) }
    }

    private fun warmUp(entry: Entry) {
        runCatching {
            warmUpPool.execute {
                runCatching { resolve(entry) }
                    .onFailure { Log.w(TAG, "Warm-up failed for ${entry.url}: $it") }
            }
        }.onFailure { Log.w(TAG, "Could not schedule warm-up: $it") }
    }

    /**
     * Fetches the track, following the playlist hop when the upstream body is
     * an HLS subtitle playlist rather than the VTT itself. Cached on success so
     * repeated reads - the player re-reads a track when it is reselected - stay
     * local.
     */
    private fun resolve(entry: Entry): ByteArray {
        entry.body?.let { return it }

        return synchronized(entry) {
            entry.body ?: run {
                val fetched = fetch(entry.url, entry.headers)
                val body = if (fetched.isHlsPlaylist()) {
                    val segment = fetched.decodeToString()
                        .lineSequence()
                        .map(String::trim)
                        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                        ?: error("Subtitle playlist listed no segment: ${entry.url}")
                    fetch(entry.url.toHttpUrl().resolve(segment)?.toString() ?: segment, entry.headers)
                } else {
                    fetched
                }
                // Keep an outlier out of the cache rather than pinning it for the
                // whole TTL; it is still served, just re-fetched if read again.
                if (body.size <= MAX_CACHED_BYTES) entry.body = body
                body
            }
        }
    }

    private fun ByteArray.isHlsPlaylist(): Boolean = size >= EXTM3U.length && decodeToString(0, EXTM3U.length) == EXTM3U

    private fun fetch(url: String, headers: Headers): ByteArray = client.newCall(
        Request.Builder().url(url).headers(headers).build(),
    ).execute().use { response ->
        if (!response.isSuccessful) error("HTTP ${response.code} for $url")
        response.body.bytes()
    }

    @Deprecated("Deprecated in Java")
    override fun serve(session: IHTTPSession): Response {
        if (!session.uri.startsWith(SUB_PATH)) {
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        val id = session.uri.removePrefix(SUB_PATH).substringBeforeLast('.')
        val entry = synchronized(entries) { entries[id] }
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")

        val body = runCatching { resolve(entry) }.getOrElse {
            Log.w(TAG, "Subtitle fetch failed for ${entry.url}: $it")
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Subtitle fetch failed: $it")
        }

        return newFixedLengthResponse(Status.OK, MIME_VTT, ByteArrayInputStream(body), body.size.toLong())
    }

    companion object {
        private const val TAG = "StreamingUnitySubs"
        private const val LOOPBACK_HOSTNAME = "127.0.0.1"
        private const val SUB_PATH = "/sub/"
        private const val MIME_VTT = "text/vtt"
        private const val EXTM3U = "#EXTM3U"

        /** Matches the lifetime of the signed `expires` carried by the upstream URLs. */
        private const val ENTRY_TTL_MS = 3 * 60 * 60 * 1000L
        private const val MAX_ENTRIES = 200
        private const val MAX_CACHED_BYTES = 2 * 1024 * 1024
        private const val WARM_UP_THREADS = 4

        @Volatile
        private var instance: SubtitleServer? = null

        private fun server(client: OkHttpClient): SubtitleServer? = instance ?: synchronized(this) {
            instance ?: runCatching { SubtitleServer(client) }
                .onFailure { Log.w(TAG, "Could not start subtitle server: $it") }
                .getOrNull()
                ?.also { instance = it }
        }

        /**
         * Deduplicates [tracks] and routes them through the shared server,
         * falling back to the upstream URLs unchanged when it cannot be started
         * - slow subtitles beat no subtitles. Shared across the four sources the
         * factory builds, so they do not each bind their own port.
         */
        fun proxy(client: OkHttpClient, tracks: List<Track>, headers: Headers): List<Track> {
            val deduped = tracks.distinctBy(Track::url)
            if (deduped.isEmpty()) return deduped

            val running = server(client) ?: return deduped
            return runCatching { running.proxy(deduped, headers) }
                .onFailure { Log.w(TAG, "Falling back to upstream subtitle URLs: $it") }
                .getOrDefault(deduped)
        }
    }
}
