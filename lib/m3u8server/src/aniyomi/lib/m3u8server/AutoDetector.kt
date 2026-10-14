package aniyomi.lib.m3u8server

/**
 * Locates obfuscation junk inside downloaded segments so the proxy can strip
 * it before serving the segment to the player.
 */
object AutoDetector {

    // Magic headers for different formats
    private val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())

    // Full GIF signatures: the bare "GIF" prefix is also a valid TS header
    // (sync byte, PUSI set, PID 0x946), so it cannot identify junk on its own.
    private val GIF87A_HEADER = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF89A_HEADER = "GIF89a".toByteArray(Charsets.US_ASCII)
    private const val MPEG_TS_SYNC = 0x47.toByte()
    private val MP4_FTYP = byteArrayOf(0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte()) // "ftyp"
    private val AVI_RIFF = byteArrayOf(0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte()) // "RIFF"
    private const val MPEG_TS_PACKET_SIZE = 188

    /**
     * Maximum distance to search for a (re)starting MPEG-TS packet grid, both
     * at the head of a segment and after an off-grid gap. Junk blocks are a
     * few hundred bytes; bounding the search keeps a random sync-looking byte
     * pattern deep inside non-TS data from being mistaken for a TS stream.
     */
    private const val SYNC_SEARCH_LIMIT = 8 * 1024

    /**
     * Returns the sorted, non-overlapping byte ranges (inclusive `[start, end]`)
     * of junk that must be stripped from a segment before it is served.
     *
     * MPEG-TS is a strict grid of 188-byte packets, so junk injected by an
     * obfuscator (a fake image header at the start, or further disguise blocks
     * between packets) always shows up as a gap in that grid. The detector
     * walks the grid and strips only the bytes between a packet and the next
     * verified packet grid, so packet payload is never inspected or altered;
     * magic bytes such as `FF D8 FF` occur by chance inside compressed audio
     * and video, and matching on them corrupts valid streams.
     *
     * Buffers that do not start a TS grid within [SYNC_SEARCH_LIMIT] (MP4, AVI,
     * fMP4) can only carry a leading disguise block, which is located with
     * [detectLeadingDisguise].
     */
    fun detectInterleavedSkips(data: ByteArray): List<IntRange> {
        if (data.isEmpty()) return emptyList()

        val tsStart = findSyncGrid(data, 0)
        if (tsStart < 0) {
            val skip = detectLeadingDisguise(data)
            return if (skip > 0) listOf(0 until skip) else emptyList()
        }

        val regions = mutableListOf<IntRange>()
        if (tsStart > 0) regions.add(0 until tsStart)

        var pos = tsStart
        while (pos < data.size) {
            // Any sync byte on the grid is a packet, including ones flagged
            // with transport_error_indicator. Only a junk block can put image
            // magic on a packet boundary, so that marks the start of a gap.
            if (data[pos] == MPEG_TS_SYNC && !hasImageMagicAt(data, pos)) {
                pos += MPEG_TS_PACKET_SIZE
                continue
            }
            val resume = findSyncGrid(data, pos + 1)
            // No grid resumes: a truncated final packet or trailing bytes we
            // cannot prove are junk, so leave them untouched.
            if (resume < 0) break
            regions.add(pos until resume)
            pos = resume
        }

        return regions
    }

    /**
     * Returns the first offset at or after [from], within [SYNC_SEARCH_LIMIT],
     * where a packet grid starts (see [isSyncGridAt]), or -1 if none.
     */
    private fun findSyncGrid(data: ByteArray, from: Int): Int {
        val searchEnd = minOf(data.size, from + SYNC_SEARCH_LIMIT)
        for (i in from until searchEnd) {
            if (isSyncGridAt(data, i)) return i
        }
        return -1
    }

    /**
     * True if a packet grid starts at [offset]: a plausible packet header
     * (sync byte, transport_error_indicator clear, non-reserved
     * adaptation_field_control) followed by two more packet slots that both
     * start with a sync byte. All three slots must fit in the buffer, so a
     * stray `0x47` near the end of a short non-TS segment is never taken for
     * a grid.
     */
    private fun isSyncGridAt(data: ByteArray, offset: Int): Boolean {
        if (offset + 2 * MPEG_TS_PACKET_SIZE >= data.size) return false
        if (data[offset] != MPEG_TS_SYNC) return false
        if (data[offset + 1].toInt() and 0x80 != 0) return false
        if (data[offset + 3].toInt() and 0x30 == 0) return false
        return data[offset + MPEG_TS_PACKET_SIZE] == MPEG_TS_SYNC &&
            data[offset + 2 * MPEG_TS_PACKET_SIZE] == MPEG_TS_SYNC
    }

    private fun hasImageMagicAt(data: ByteArray, offset: Int): Boolean = matchesAt(data, offset, JPEG_HEADER) ||
        matchesAt(data, offset, PNG_HEADER) ||
        matchesAt(data, offset, GIF87A_HEADER) ||
        matchesAt(data, offset, GIF89A_HEADER)

    /**
     * For a non-TS buffer that starts with JPEG / PNG / GIF magic, returns the
     * offset of the real MP4 or AVI container hidden behind it; 0 otherwise.
     */
    private fun detectLeadingDisguise(data: ByteArray): Int {
        if (!hasImageMagicAt(data, 0)) {
            return 0
        }

        val ftypOffset = findPattern(data, MP4_FTYP)
        if (ftypOffset >= 4) {
            return ftypOffset - 4 // "ftyp" is preceded by 4 bytes of size
        }

        val riffOffset = findPattern(data, AVI_RIFF)
        if (riffOffset > 0) {
            return riffOffset
        }

        return 0
    }

    private fun matchesAt(data: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset + pattern.size > data.size) return false
        for (i in pattern.indices) {
            if (data[offset + i] != pattern[i]) return false
        }
        return true
    }

    private fun findPattern(data: ByteArray, pattern: ByteArray): Int {
        for (i in 0..data.size - pattern.size) {
            if (matchesAt(data, i, pattern)) return i
        }
        return -1
    }
}
