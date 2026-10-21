package eu.kanade.tachiyomi.animeextension.en.moviesmod

import eu.kanade.tachiyomi.network.GET
import keiyoushi.network.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.BufferedSource
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves Instant and Direct links to the player as seekable files over loopback, backed by the
 * Resume Cloud zip of the same file.
 *
 * Instant links end at Google's UploadServer, which ignores Range, so the player can't seek in them.
 * They play from the start right away while the zip is generated in the background, and ranges far
 * from the start are taken from the zip once it's ready. If it can't be had, they are read through
 * from the start of the Instant link.
 *
 * Direct links honour Range, but they are Google Drive API calls on a shared quota, which often runs
 * out partway through a video. Once a Direct link fails, the zip is generated and serves the rest.
 *
 * The Resume Cloud worker honours Range but wraps the file in a zip. That zip deflates the file into
 * stored (uncompressed) blocks of a fixed size, so every byte of the video sits at a known offset of
 * the zip, and a range of the video is served by dropping the 5-byte header in front of each block.
 */
object VideoServer : NanoHTTPD("127.0.0.1", 0) {

    private const val BLOCK_SIZE = 32768
    private const val BLOCK_HEADER_SIZE = 5
    private const val PROBE_SIZE = 4096L
    private const val HEAD_SIZE = 65536L
    private const val DIRECT_SKIP_LIMIT = 16L * 1024 * 1024
    private const val ZIP_WAIT_SECONDS = 45L

    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CLUSTER = 0x1F43B675L
    private val DEFERRABLE = setOf(0x1254C367L, 0x1043A770L, 0x1941A469L) // Tags, Chapters, Attachments

    private class Zip(val url: String, val dataStart: Long, val size: Long)

    private class Patch(val position: Long, val bytes: ByteArray)

    private class Entry(
        val client: OkHttpClient,
        val headers: Headers,
        val size: Long,
        val mime: String,
        /** A link that honours Range, used while it works. */
        val rangeUrl: String?,
        /** A link that ignores Range, read through from the start. */
        val streamUrl: String?,
        val patches: List<Patch>,
        val findZip: suspend () -> String?,
    ) {
        @Volatile
        var rangeAlive = rangeUrl != null

        @Volatile
        var zip: Zip? = null
        val zipRequested = AtomicBoolean(false)
        val zipSettled = CountDownLatch(1)
    }

    // Only registered files are served, so the port can't be used to fetch arbitrary URLs.
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 32
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Returns the loopback URL of [videoUrl], or `null` if it can't be served.
     *
     * @param videoUrl an Instant link, which ignores Range, or a Direct link, which honours it
     * @param findZip resolves the Resume Cloud zip of the same file
     */
    suspend fun register(client: OkHttpClient, headers: Headers, videoUrl: String, findZip: suspend () -> String?): String? {
        val id = idOf(videoUrl)
        if (synchronized(this) { entries[id] } != null) return urlOf(id)

        // Streamed files must not end up in the disk cache of the shared client.
        val streamClient = client.newBuilder().cache(null).build()
        val rangeHeaders = headers.newBuilder().set("Range", "bytes=0-${HEAD_SIZE - 1}").build()
        val entry = streamClient.get(videoUrl, rangeHeaders, CacheControl.FORCE_NETWORK, ensureSuccess = false).use { response ->
            val type = response.body.contentType()
            if (type == null || type.type == "text" || type.subtype == "json") return null
            when (response.code) {
                206 -> {
                    val size = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: return null
                    Entry(streamClient, headers, size, type.toString(), videoUrl, null, emptyList(), findZip)
                }
                200 -> {
                    val size = response.body.contentLength().takeIf { it > 0 } ?: return null
                    val source = response.body.source()
                    source.request(HEAD_SIZE)
                    val head = source.buffer.readByteArray(minOf(source.buffer.size, HEAD_SIZE))
                    Entry(streamClient, headers, size, type.toString(), null, videoUrl, seekHeadPatches(head), findZip)
                }
                else -> return null
            }
        }
        synchronized(this) {
            if (!isAlive) start()
            entries[id] = entry
        }
        if (entry.rangeUrl == null) entry.requestZip()
        return urlOf(id)
    }

    private fun Entry.requestZip() {
        if (!zipRequested.compareAndSet(false, true)) return
        scope.launch {
            zip = runCatching { findZip()?.let { probeZip(client, headers, it) } }.getOrNull()?.takeIf { it.size == size }
            zipSettled.countDown()
        }
    }

    private fun idOf(key: String) = UUID.nameUUIDFromBytes(key.toByteArray()).toString()

    private fun urlOf(id: String) = "http://127.0.0.1:$listeningPort/$id"

    override fun handle(session: IHTTPSession): Response {
        val entry = synchronized(this) { entries[session.uri.trim('/')] }
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        return try {
            serve(entry, session.headers["range"])
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.toString())
        }
    }

    private fun serve(entry: Entry, rangeHeader: String?): Response {
        val range = rangeHeader?.trim()?.let { RANGE_REGEX.matchEntire(it) }
        var start = 0L
        var end = entry.size - 1
        if (range != null) {
            val (from, to) = range.destructured
            if (from.isEmpty()) {
                start = (entry.size - (to.toLongOrNull() ?: 0)).coerceAtLeast(0)
            } else {
                start = from.toLongOrNull() ?: Long.MAX_VALUE
                end = to.toLongOrNull()?.coerceAtMost(end) ?: end
            }
            if (start > end) {
                return newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    .apply { addHeader("Content-Range", "bytes */${entry.size}") }
            }
        }

        val input = open(entry, start, end)
        val length = end - start + 1
        val data = if (entry.patches.isEmpty()) input else PatchedInputStream(input, start, entry.patches)
        val status = if (range == null) Status.OK else Status.PARTIAL_CONTENT
        return newFixedLengthResponse(status, entry.mime, data, length).apply {
            addHeader("Accept-Ranges", "bytes")
            if (range != null) addHeader("Content-Range", "bytes $start-$end/${entry.size}")
        }
    }

    private fun open(entry: Entry, start: Long, end: Long): InputStream {
        if (entry.rangeAlive) {
            openRange(entry, entry.rangeUrl!!, start, end)?.let { return it }
            entry.rangeAlive = false
            entry.requestZip()
        }
        // Reading through the stream link costs as much as the offset, so far ranges wait for the zip.
        if (entry.zip == null && (entry.streamUrl == null || start > DIRECT_SKIP_LIMIT)) {
            entry.zipSettled.await(ZIP_WAIT_SECONDS, TimeUnit.SECONDS)
        }
        entry.zip?.let { return openZip(entry, it, start, end) }
        val streamUrl = entry.streamUrl ?: throw IOException("The link failed and there is no zip to fall back on")
        return openStream(entry, streamUrl, start)
    }

    private fun openRange(entry: Entry, url: String, start: Long, end: Long): InputStream? {
        val rangeHeaders = entry.headers.newBuilder().set("Range", "bytes=$start-$end").build()
        val response = runCatching { entry.client.newCall(GET(url, rangeHeaders)).execute() }.getOrNull() ?: return null
        val type = response.body.contentType()
        if (response.code != 206 || type == null || type.type == "text" || type.subtype == "json") {
            response.close()
            return null
        }
        return response.body.byteStream()
    }

    private fun openZip(entry: Entry, zip: Zip, start: Long, end: Long): InputStream {
        val zipRange = "bytes=${zip.offsetOf(start)}-${zip.offsetOf(end)}"
        val response = entry.client.newCall(GET(zip.url, entry.headers.newBuilder().set("Range", zipRange).build())).execute()
        if (response.code != 206) {
            response.close()
            throw IOException("HTTP ${response.code} for the zip")
        }
        return StoredBlockInputStream(response.body.source(), BLOCK_SIZE - (start % BLOCK_SIZE).toInt())
    }

    private fun openStream(entry: Entry, url: String, start: Long): InputStream {
        val response = entry.client.newCall(GET(url, entry.headers)).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code} for the stream link")
        }
        val source = response.body.source()
        try {
            source.skip(start)
        } catch (e: IOException) {
            source.close()
            throw e
        }
        return source.inputStream()
    }

    private fun Zip.offsetOf(position: Long): Long = dataStart + (position / BLOCK_SIZE) * (BLOCK_SIZE + BLOCK_HEADER_SIZE) + BLOCK_HEADER_SIZE + position % BLOCK_SIZE

    private suspend fun probeZip(client: OkHttpClient, headers: Headers, zipUrl: String): Zip? {
        val head = client.fetchRange(headers, zipUrl, "0-${PROBE_SIZE - 1}")
        val total = head.total ?: return null
        val local = head.bytes.littleEndian()
        if (local.int(0) != 0x04034b50 || local.short(8) != 8) return null
        val dataStart = 30L + local.short(26) + local.short(28)
        if (dataStart + BLOCK_HEADER_SIZE > head.bytes.size) return null

        val tail = client.fetchRange(headers, zipUrl, "-${minOf(PROBE_SIZE, total)}").bytes
        val (compressed, size) = centralDirectorySizes(tail) ?: return null
        // Stored blocks only: each full block adds its header, plus at most one empty final block.
        val blocks = (size + BLOCK_SIZE - 1) / BLOCK_SIZE
        if (compressed - size != blocks * BLOCK_HEADER_SIZE && compressed - size != (blocks + 1) * BLOCK_HEADER_SIZE) return null
        val firstStored = (head.bytes[dataStart.toInt()].toInt() and 0x06) == 0
        if (!firstStored || (size > BLOCK_SIZE && local.short(dataStart.toInt() + 1) != BLOCK_SIZE)) return null
        return Zip(zipUrl, dataStart, size)
    }

    /** Reads the compressed and uncompressed size of the first entry of the central directory. */
    private fun centralDirectorySizes(tail: ByteArray): Pair<Long, Long>? {
        val buffer = tail.littleEndian()
        val offset = (0..tail.size - 46).firstOrNull { buffer.int(it) == 0x02014b50 } ?: return null
        var compressed = buffer.int(offset + 20).toLong() and 0xFFFFFFFFL
        var size = buffer.int(offset + 24).toLong() and 0xFFFFFFFFL
        val extraStart = offset + 46 + buffer.short(offset + 28)
        val extraEnd = (extraStart + buffer.short(offset + 30)).coerceAtMost(tail.size)

        // ZIP64 stores the sizes that don't fit 32 bits in its extra field, uncompressed first.
        var field = extraStart
        while (field + 4 <= extraEnd) {
            val fieldSize = buffer.short(field + 2)
            if (buffer.short(field) == 0x0001) {
                var value = field + 4
                if (size == 0xFFFFFFFFL && value + 8 <= extraEnd) size = buffer.getLong(value).also { value += 8 }
                if (compressed == 0xFFFFFFFFL && value + 8 <= extraEnd) compressed = buffer.getLong(value)
            }
            field += 4 + fieldSize
        }
        return if (size == 0xFFFFFFFFL || compressed == 0xFFFFFFFFL) null else compressed to size
    }

    /**
     * The player reads the level 1 elements of a Matroska SeekHead when it opens the file, except
     * when only the Cues are left, which it reads on the first seek. Tags and the like at the end of
     * the file would hold up the start until the zip is ready, so their entries are pointed at a
     * Cluster instead, which players skip in a SeekHead.
     */
    private fun seekHeadPatches(head: ByteArray): List<Patch> {
        try {
            val ebml = head.ebmlElement(0)
            if (ebml.id != EBML) return emptyList()
            val segment = head.ebmlElement(ebml.end)
            if (segment.id != SEGMENT) return emptyList()
            var i = segment.dataStart
            while (i < head.size) {
                val element = head.ebmlElement(i)
                if (element.id == CLUSTER) break
                if (element.id == SEEK_HEAD) return seekEntryPatches(head, element, segment.dataStart.toLong())
                i = element.end
            }
        } catch (_: Exception) {
            // Not Matroska, or a header that doesn't fit the probed bytes
        }
        return emptyList()
    }

    private fun seekEntryPatches(head: ByteArray, seekHead: EbmlElement, segmentStart: Long): List<Patch> = buildList {
        var i = seekHead.dataStart
        while (i < seekHead.end) {
            val seek = head.ebmlElement(i)
            i = seek.end
            if (seek.id != SEEK) continue
            var idElement: EbmlElement? = null
            var position: Long? = null
            var j = seek.dataStart
            while (j < seek.end) {
                val child = head.ebmlElement(j)
                when (child.id) {
                    SEEK_ID -> idElement = child
                    SEEK_POSITION -> position = head.uint(child)
                }
                j = child.end
            }
            val target = idElement ?: continue
            if (head.uint(target) in DEFERRABLE && target.end - target.dataStart == 4 && segmentStart + (position ?: 0) > DIRECT_SKIP_LIMIT) {
                add(Patch(target.dataStart.toLong(), ByteBuffer.allocate(4).putInt(CLUSTER.toInt()).array()))
            }
        }
    }

    private class EbmlElement(val id: Long, val dataStart: Int, val end: Int)

    private fun ByteArray.ebmlElement(index: Int): EbmlElement {
        val (id, idLength) = vint(index, keepMarker = true)
        val (size, sizeLength) = vint(index + idLength, keepMarker = false)
        val dataStart = index + idLength + sizeLength
        return EbmlElement(id, dataStart, (dataStart + size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun ByteArray.uint(element: EbmlElement): Long = (element.dataStart until element.end).fold(0L) { acc, k -> (acc shl 8) or (this[k].toLong() and 0xFF) }

    private fun ByteArray.vint(index: Int, keepMarker: Boolean): Pair<Long, Int> {
        val first = this[index].toInt() and 0xFF
        val length = Integer.numberOfLeadingZeros(first) - 23
        check(length in 1..8)
        var value = if (keepMarker) first.toLong() else (first and (0xFF shr length)).toLong()
        for (k in 1 until length) value = (value shl 8) or (this[index + k].toLong() and 0xFF)
        return value to length
    }

    private class Fetched(val bytes: ByteArray, val total: Long?)

    private suspend fun OkHttpClient.fetchRange(headers: Headers, url: String, range: String): Fetched {
        val rangeHeaders = headers.newBuilder().set("Range", "bytes=$range").build()
        return get(url, rangeHeaders, CacheControl.FORCE_NETWORK).use { response ->
            check(response.code == 206) { "Range not supported (HTTP ${response.code})" }
            val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            Fetched(response.body.bytes(), total)
        }
    }

    private fun ByteArray.littleEndian(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteBuffer.int(index: Int): Int = getInt(index)

    private fun ByteBuffer.short(index: Int): Int = getShort(index).toInt() and 0xFFFF

    private val RANGE_REGEX = Regex("""bytes=(\d*)-(\d*)""")

    /** Reads stored deflate data, skipping the block header in front of every block. */
    private class StoredBlockInputStream(
        private val source: BufferedSource,
        private var blockLeft: Int,
    ) : InputStream() {
        private val single = ByteArray(1)

        override fun read(): Int = if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (blockLeft == 0) {
                if (source.exhausted()) return -1
                source.skip(BLOCK_HEADER_SIZE.toLong())
                blockLeft = BLOCK_SIZE
            }
            val read = source.read(b, off, minOf(len, blockLeft))
            if (read > 0) blockLeft -= read
            return read
        }

        override fun close() = source.close()
    }

    /** Overwrites the bytes at the positions of [patches] while reading from [position] on. */
    private class PatchedInputStream(
        private val input: InputStream,
        private var position: Long,
        private val patches: List<Patch>,
    ) : InputStream() {
        private val single = ByteArray(1)

        override fun read(): Int = if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = input.read(b, off, len)
            if (read <= 0) return read
            for (patch in patches) {
                for (k in patch.bytes.indices) {
                    val at = patch.position + k - position
                    if (at >= 0 && at < read) b[off + at.toInt()] = patch.bytes[k]
                }
            }
            position += read
            return read
        }

        override fun close() = input.close()
    }
}
