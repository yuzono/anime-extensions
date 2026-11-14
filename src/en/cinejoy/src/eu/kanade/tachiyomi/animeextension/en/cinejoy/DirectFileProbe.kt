package eu.kanade.tachiyomi.animeextension.en.cinejoy

import keiyoushi.network.get
import kotlinx.coroutines.CancellationException
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.min

/**
 * Best-effort quality probe for the direct (non-HLS/DASH) file links some
 * wing.st servers hand out. Their payload carries no quality metadata,
 * so it is recovered from the file itself, which starts with either
 * the original scene filename exposed in `Content-Disposition` (e.g.
 * "…2160p….mkv") or the container header: MP4 `tkhd` and Matroska
 * `PixelWidth`/`PixelHeight` both sit in the first few kilobytes.
 *
 * Probe URLs come from the decrypted wing.st payload rather than a fixed
 * catalog, so they are guarded against loopback/LAN targets: [probeClient]
 * rejects every host that resolves to a non-public address — enforced at
 * connect time, which covers each redirect hop as well — and [quality]
 * additionally pre-checks the scheme and literal-IP hosts before connecting.
 */
internal class DirectFileProbe(
    client: OkHttpClient,
    headers: Headers,
) {

    private val probeHeaders = headers.newBuilder()
        .set("Range", "bytes=0-${PROBE_SIZE - 1}")
        .build()

    private val probeClient = client.withPublicHostGuard()

    /**
     * Returns a standard quality label ("1080p") or null when the file can't
     * be reached or its header can't be parsed.
     */
    suspend fun quality(url: String): String? = try {
        if (!isProbeableUrl(url)) return null

        probeClient.get(url, probeHeaders, ensureSuccess = false).use { response ->
            if (!response.isSuccessful) return null

            // The filename rides in the headers; skip the body read entirely
            // when it already answers the quality question.
            fileNameQuality(response.header("Content-Disposition"))?.let { return it }

            val head = response.body.byteStream().use { input ->
                val buf = ByteArray(PROBE_SIZE)
                var read = 0
                while (read < PROBE_SIZE) {
                    val count = input.read(buf, read, PROBE_SIZE - read)
                    if (count == -1) break
                    read += count
                }
                if (read == PROBE_SIZE) buf else buf.copyOf(read)
            }

            containerQuality(head)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun fileNameQuality(disposition: String?): String? {
        val name = disposition?.let { FILE_NAME_REGEX.find(it)?.groupValues?.get(1) } ?: return null
        FILE_QUALITY_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { return standardLabel(it) }
        if (FOUR_K_REGEX.containsMatchIn(name)) return "2160p"
        return null
    }

    private fun containerQuality(head: ByteArray): String? {
        val (width, height) = when {
            head.size >= 12 && boxType(head, 4) == "ftyp" -> mp4Dimensions(head)
            head.size >= 4 && head[0] == 0x1A.toByte() &&
                head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() &&
                head[3] == 0xA3.toByte() -> matroskaDimensions(head)
            else -> null
        } ?: return null
        return standardLabel(if (height > 0) height else width)
    }

    /** Snaps a height to the standard qualities PlaylistUtils labels with. */
    private fun standardLabel(height: Int): String? = when (height) {
        0 -> null
        else -> STANDARD_QUALITIES.minByOrNull { abs(it - height) }?.let { "${it}p" }
    }

    // ========================== MP4 (moov/tkhd) ===========================

    /**
     * `tkhd` ends with width and height as 16.16 fixed-point integers in the
     * last 8 bytes of the box. The first track isn't necessarily the video
     * (chapter/data tracks carry a zero-size header), so every `tkhd` is
     * checked and the first one with usable dimensions wins.
     */
    private fun mp4Dimensions(head: ByteArray): Pair<Int, Int>? {
        var dims: Pair<Int, Int>? = null
        forEachBox(head, 0, head.size, "tkhd", CONTAINER_BOXES) { start, end ->
            if (dims == null && end - start >= 8) {
                val width = readInt(head, end - 8) ushr 16
                val height = readInt(head, end - 4) ushr 16
                if (width > 0 || height > 0) dims = width to height
            }
        }
        return dims
    }

    /** Invokes `onFound` for every box of type `target`, descending into `containers`. */
    private fun forEachBox(
        head: ByteArray,
        start: Int,
        end: Int,
        target: String,
        containers: Set<String>,
        onFound: (Int, Int) -> Unit,
    ) {
        var pos = start
        while (pos + 8 <= end) {
            var size = readInt(head, pos)
            var header = 8
            when (size) {
                1 -> {
                    if (pos + 16 > end) return
                    size = readLong(head, pos + 8).toInt()
                    header = 16
                }
                0 -> size = end - pos // extends to the end of the file
            }
            if (size < header || pos + size > end) return

            val type = boxType(head, pos + 4)
            if (type == target) {
                onFound(pos + header, pos + size)
            } else if (type in containers) {
                forEachBox(head, pos + header, pos + size, target, containers, onFound)
            }
            pos += size
        }
    }

    // =================== Matroska / WebM (EBML tracks) ====================
    private fun matroskaDimensions(head: ByteArray): Pair<Int, Int>? = walkEbml(
        head,
        0,
        head.size,
        ID_SEGMENT,
        ID_TRACKS,
        ID_TRACK_ENTRY,
    )?.takeIf { (width, height) -> width > 0 && height > 0 }

    /**
     * Recurses the ids in `pathIds` down to a Video element and reads its
     * `PixelWidth`/`PixelHeight` from the first video track found.
     */
    private fun walkEbml(
        head: ByteArray,
        start: Int,
        end: Int,
        vararg pathIds: Long,
    ): Pair<Int, Int>? {
        var pos = start
        while (pos + 2 < end) {
            val idLength = ebmlVintLength(head, pos) ?: return null
            val id = ebmlId(head, pos, idLength) ?: return null
            val sizePos = pos + idLength
            val sizeLength = ebmlVintLength(head, sizePos) ?: return null
            val size = ebmlSize(head, sizePos, sizeLength) ?: return null
            val dataStart = sizePos + sizeLength
            // An all-ones size vint means "unknown" (whole rest of the
            // parent); truncated tails are clamped to the buffer. The size
            // is clamped as a Long first - Segment sizes regularly exceed
            // Int range, and converting first would wrap the low 32 bits.
            val dataEnd = if (size < 0) end else min(dataStart + size, end.toLong()).toInt()

            when {
                pathIds.isNotEmpty() && id == pathIds[0] ->
                    walkEbml(head, dataStart, dataEnd, *pathIds.drop(1).toLongArray())?.let { return it }
                id == ID_VIDEO -> return readVideoSize(head, dataStart, dataEnd)
            }
            pos = dataEnd
        }
        return null
    }

    private fun readVideoSize(head: ByteArray, start: Int, end: Int): Pair<Int, Int> {
        var width = 0
        var height = 0
        var pos = start
        while (pos + 2 < end) {
            val idLength = ebmlVintLength(head, pos) ?: break
            val id = ebmlId(head, pos, idLength) ?: break
            val sizePos = pos + idLength
            val sizeLength = ebmlVintLength(head, sizePos) ?: break
            val size = ebmlSize(head, sizePos, sizeLength) ?: break
            val dataStart = sizePos + sizeLength
            when (id) {
                ID_PIXEL_WIDTH -> width = ebmlDataInt(head, dataStart, size)
                ID_PIXEL_HEIGHT -> height = ebmlDataInt(head, dataStart, size)
            }
            if (size < 0) break
            pos = dataStart + size.toInt()
        }
        return width to height
    }

    // =============================== Helpers ==============================

    /**
     * Cheap pre-flight for probe URLs: only http(s), and if the host is an
     * IP literal it must be public. Hostnames are validated when [probeClient]
     * resolves them for the connection (redirects included).
     */
    private fun isProbeableUrl(url: String): Boolean = try {
        val httpUrl = url.toHttpUrl()
        val literal = literalAddress(httpUrl.host)
        (httpUrl.scheme == "http" || httpUrl.scheme == "https") &&
            (literal == null || literal.isPublic)
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Length of an EBML vint (element id or size), derived from its marker bits. */
    private fun ebmlVintLength(head: ByteArray, pos: Int): Int? {
        if (pos >= head.size) return null
        val first = head[pos].toInt() and 0xFF
        var length = 1
        var mask = 0x80
        while (length <= 8 && first and mask == 0) {
            mask = mask ushr 1
            length++
        }
        return if (length > 8 || pos + length > head.size) null else length
    }

    /** Raw vint value (marker bits kept) — element ids are matched as-is. */
    private fun ebmlId(head: ByteArray, pos: Int, length: Int): Long? {
        if (pos + length > head.size) return null
        var value = 0L
        for (i in 0 until length) {
            value = (value shl 8) or (head[pos + i].toLong() and 0xFF)
        }
        return value
    }

    /** Data-size vint: marker bits stripped; an all-ones vint returns -1 (unknown). */
    private fun ebmlSize(head: ByteArray, pos: Int, length: Int): Long? {
        if (pos + length > head.size) return null
        var raw = 0L
        for (i in 0 until length) {
            raw = (raw shl 8) or (head[pos + i].toLong() and 0xFF)
        }
        // The "unknown size" sentinel is a vint with every bit set. Since
        // [ebmlVintLength] guarantees the marker bit is set, that's
        // equivalent to the marker-stripped payload being all ones - which
        // also works for the 8-byte case, where shifting by 64 would wrap.
        val payloadMask = (1L shl (7 * length)) - 1
        val payload = raw and payloadMask
        return if (payload == payloadMask) -1L else payload
    }

    /** Big-endian integer of up to 8 bytes as stored in an EBML data payload. */
    private fun ebmlDataInt(head: ByteArray, pos: Int, length: Long): Int {
        val bytes = min(length, 8L).toInt()
        if (pos + bytes > head.size) return 0
        var value = 0L
        for (i in 0 until bytes) {
            value = (value shl 8) or (head[pos + i].toLong() and 0xFF)
        }
        return value.toInt()
    }

    private fun boxType(head: ByteArray, pos: Int): String? {
        if (pos + 4 > head.size) return null
        val bytes = head.copyOfRange(pos, pos + 4)
        if (!bytes.all { it in 32..126 }) return null
        return String(bytes, StandardCharsets.US_ASCII)
    }

    private fun readInt(head: ByteArray, pos: Int): Int {
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (head[pos + i].toInt() and 0xFF)
        return value
    }

    private fun readLong(head: ByteArray, pos: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (head[pos + i].toLong() and 0xFF)
        return value
    }

    companion object {
        private const val PROBE_SIZE = 2 * 1024 * 1024

        private const val ID_SEGMENT = 0x18538067L
        private const val ID_TRACKS = 0x1654AE6BL
        private const val ID_TRACK_ENTRY = 0xAEL
        private const val ID_VIDEO = 0xE0L
        private const val ID_PIXEL_WIDTH = 0xB0L
        private const val ID_PIXEL_HEIGHT = 0xBAL

        private val CONTAINER_BOXES = setOf("moov", "trak")

        private val FILE_NAME_REGEX = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""")
        private val FILE_QUALITY_REGEX = Regex("""(\d{3,4})[pP]""")
        private val FOUR_K_REGEX = Regex("""\b4k\b""", RegexOption.IGNORE_CASE)

        private val STANDARD_QUALITIES = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
    }
}

/**
 * Returns a client that refuses connections to hosts resolving to
 * non-public addresses (loopback, LAN, link-local, ULA, CGNAT, multicast).
 * Hostnames are validated when the DNS layer resolves them for the
 * connection; IP literals never reach the Dns callback (RouteSelector
 * parses them directly), so a network interceptor re-checks them for
 * every request, redirects included. Wing.st stream URLs come from a
 * decrypted payload rather than a fixed catalog; an explicit host
 * allowlist would break on the CDNs' frequent rotation, so public
 * reachability is defined by address ranges instead.
 */
internal fun OkHttpClient.withPublicHostGuard(): OkHttpClient = newBuilder()
    .dns { host ->
        val addresses = runCatching { Dns.SYSTEM.lookup(host) }
            .getOrElse { throw UnknownHostException("Unresolvable host: $host") }
        if (addresses.isEmpty() || addresses.any { !it.isPublic }) {
            throw UnknownHostException("Blocked non-public host: $host")
        }
        addresses
    }
    .addNetworkInterceptor { chain ->
        val url = chain.request().url
        literalAddress(url.host)
            ?.takeIf { !it.isPublic }
            ?.let { throw UnknownHostException("Blocked non-public host: ${url.host}") }
        chain.proceed(chain.request())
    }
    .build()

private val IPV4_REGEX = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/** Parses a host as an IP literal (no DNS lookup involved), or null for a hostname. */
private fun literalAddress(host: String): InetAddress? {
    if (IPV4_REGEX.matches(host) || host.contains(':')) {
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }
    return null
}

private val InetAddress.isPublic: Boolean
    get() {
        if (isLoopbackAddress || isAnyLocalAddress ||
            isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress
        ) {
            return false
        }

        val bytes = address
        return when (bytes.size) {
            4 -> {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                first != 0 && !(first == 100 && second in 64..127) // 0/8, CGNAT 100.64/10
            }
            16 -> when {
                (bytes[0].toInt() and 0xFF) in 0xFC..0xFD -> false // unique local fc00::/7
                bytes.copyOfRange(0, 10).all { it.toInt() == 0 } &&
                    bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte() ->
                    // IPv4-mapped ::ffff:a.b.c.d — check the embedded address
                    InetAddress.getByAddress(bytes.copyOfRange(12, 16)).isPublic
                else -> true
            }
            else -> false
        }
    }
