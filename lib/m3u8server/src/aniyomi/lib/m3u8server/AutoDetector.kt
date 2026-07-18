package aniyomi.lib.m3u8server

/**
 * Automatic file format detector and offset calculator
 */
object AutoDetector {

    // Magic headers for different formats
    private val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
    private val GIF_HEADER = byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte())
    private const val MPEG_TS_SYNC = 0x47.toByte()
    private val MP4_FTYP = byteArrayOf(0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte()) // "ftyp"
    private val AVI_RIFF = byteArrayOf(0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte()) // "RIFF"
    private val AVI_AVI = byteArrayOf(0x41.toByte(), 0x56.toByte(), 0x49.toByte(), 0x20.toByte()) // "AVI "
    private const val MPEG_TS_PACKET_SIZE = 188

    /**
     * Standard length of a single fake-header junk block. The obfuscators that
     * inject fake image magic bytes (ibyteimg / tiktokcdn variants observed in
     * ChillX-style streams) prepend a fixed 252-byte disguise chunk before
     * resuming the real format. This value is matched by [JunkBytesInterceptor]
     * in lib-multisrc/anikototheme and serves as the default junk-block length
     * when we can't positively identify where the junk ends.
     */
    private const val DEFAULT_JUNK_BLOCK_SIZE = 252

    /**
     * Maximum distance from a junk-magic header to search for the next valid
     * video format boundary when determining the end of the junk block. Capped
     * at 8 KB so a stray magic header embedded inside a long real segment does
     * not blow up the scan into a buffer-size chase.
     */
    private const val JUNK_END_SEARCH_LIMIT = 8 * 1024

    /**
     * Automatically detects how many bytes to skip at the beginning of the file.
     *
     * NOTE: This is the legacy prefix-only entry point — it inspects only the
     * leading portion of the buffer and returns a single offset. For segments
     * potentially containing junk blocks interleaved past the first 4 KB (the
     * ChillX-style obfuscation pattern), use [detectInterleavedSkips], which
     * scans the entire buffer and returns a list of regions to strip.
     *
     * @param data File data (first 4KB is sufficient)
     * @return Number of bytes to skip
     */
    fun detectSkipBytes(data: ByteArray): Int {
        if (data.isEmpty()) return 0

        return when {
            // If it's already a valid MPEG-TS, don't need to skip anything
            isMpegTsValid(data) -> 0

            // If it's JPEG/PNG/GIF disguising another format
            isJpegHeader(data) || isPngHeader(data) || isGifHeader(data) -> detectDisguise(data)

            // If it's already a valid video format
            isVideoFormat(data) -> 0

            // Unrecognized pattern, don't skip anything
            else -> 0
        }
    }

    /**
     * Scans the ENTIRE buffer for fake-image junk blocks (JPEG / PNG / GIF magic
     * bytes) inserted at any offset to disguise the underlying video stream, and
     * returns the sorted, non-overlapping list of byte ranges that should be
     * stripped from the buffer before the result is served to the player.
     *
     * Each returned [IntRange] is an inclusive-inclusive `[start, end]` pair
     * (Kotlin's default `IntRange` semantics) covering bytes that are垃圾 data
     * and must NOT be forwarded. Callers re-assemble the segment by keeping
     * every byte NOT covered by any returned range, in order.
     *
     * ### Detection algorithm
     *
     * For every offset `i` in the buffer whose 3-4 byte prefix matches JPEG /
     * PNG / GIF magic, the detector walks forward up to [JUNK_END_SEARCH_LIMIT]
     * bytes looking for a valid **video format boundary**:
     *  * MPEG-TS：a 0x47 sync byte followed by at least 2 more 0x47 bytes at
     *    188-byte strides (validated by [isMpegTsValidAt])
     *  * MP4：a 4-byte big-endian size followed by "ftyp"
     *  * AVI：a "RIFF" marker immediately followed by "AVI "
     *
     * If a boundary is found within the search window, the junk block ends
     * just before that boundary. If no boundary is found, the junk block is
     * assumed to be a fixed [DEFAULT_JUNK_BLOCK_SIZE] bytes long (matching the
     * pattern produced by the most-common ibyteimg/tiktokcdn injectors).
     *
     * Junk blocks that overlap (or that fall entirely inside another junk
     * region) are merged. Ranges starting past the buffer end are dropped.
     *
     * ### Why full-buffer scan
     *
     * The previous [detectSkipBytes] path only inspected the first 4 KB of a
     * segment (the size of the single read performed by the legacy
     * fetchSegmentWithAutoDetection path). ChillX-style obfuscators
     * insert additional junk blocks deeper into the segment — past the 4 KB
     * prefix — so the legacy path silently missed them. This entry point
     * operates on the full downloaded segment buffer.
     *
     * @param data Full segment buffer (NOT truncated to 4 KB)
     * @return Sorted, non-overlapping [IntRange]s of junk bytes to strip.
     *         Empty list if no junk is detected.
     */
    fun detectInterleavedSkips(data: ByteArray): List<IntRange> {
        if (data.isEmpty()) return emptyList()

        val regions = mutableListOf<IntRange>()

        var i = 0
        while (i < data.size - 2) {
            val junkEnd = detectJunkBlockEnd(data, i)
            if (junkEnd >= 0) {
                if (junkEnd > i) {
                    regions.add(i until junkEnd)
                }
                i = junkEnd
            } else {
                i++
            }
        }

        return mergeRegions(regions)
    }

    /**
     * If [offset] looks like the start of a fake-image junk block, returns
     * the EXCLUSIVE end offset (i.e. the index of the first byte AFTER the
     * junk block). Returns -1 if [offset] is not a junk-magic header.
     *
     * The returned end is always `> offset` for a real junk block, and is
     * clamped to [data.size].
     */
    private fun detectJunkBlockEnd(data: ByteArray, offset: Int): Int {
        val isJpeg = offset + 2 < data.size &&
            data[offset] == JPEG_HEADER[0] &&
            data[offset + 1] == JPEG_HEADER[1] &&
            data[offset + 2] == JPEG_HEADER[2]
        val isPng = offset + 3 < data.size &&
            data[offset] == PNG_HEADER[0] &&
            data[offset + 1] == PNG_HEADER[1] &&
            data[offset + 2] == PNG_HEADER[2] &&
            data[offset + 3] == PNG_HEADER[3]
        val isGif = offset + 2 < data.size &&
            data[offset] == GIF_HEADER[0] &&
            data[offset + 1] == GIF_HEADER[1] &&
            data[offset + 2] == GIF_HEADER[2]

        if (!isJpeg && !isPng && !isGif) return -1

        val searchEnd = minOf(data.size, offset + JUNK_END_SEARCH_LIMIT)

        var i = offset + 1
        while (i < searchEnd) {
            if (i + 8 <= searchEnd && isFtypAt(data, i)) {
                return i
            }
            if (i + 12 <= searchEnd && isRiffAviAt(data, i)) {
                return i
            }
            if (data[i] == MPEG_TS_SYNC && isMpegTsValidAt(data, i)) {
                return i
            }
            i++
        }

        return minOf(data.size, offset + DEFAULT_JUNK_BLOCK_SIZE)
    }

    /**
     * Returns true if an MP4 "ftyp" atom begins at [offset]. The 4-byte size at
     * [offset, offset+3] is intentionally NOT validated. Caller MUST ensure
     * `offset + 8 <= data.size` before invoking.
     */
    private fun isFtypAt(data: ByteArray, offset: Int): Boolean = data[offset + 4] == MP4_FTYP[0] &&
        data[offset + 5] == MP4_FTYP[1] &&
        data[offset + 6] == MP4_FTYP[2] &&
        data[offset + 7] == MP4_FTYP[3]

    /**
     * Returns true if a RIFF/AVI magic block begins at [offset]. Caller MUST
     * ensure `offset + 12 <= data.size` before invoking.
     */
    private fun isRiffAviAt(data: ByteArray, offset: Int): Boolean = data[offset] == AVI_RIFF[0] &&
        data[offset + 1] == AVI_RIFF[1] &&
        data[offset + 2] == AVI_RIFF[2] &&
        data[offset + 3] == AVI_RIFF[3] &&
        data[offset + 8] == AVI_AVI[0] &&
        data[offset + 9] == AVI_AVI[1] &&
        data[offset + 10] == AVI_AVI[2] &&
        data[offset + 11] == AVI_AVI[3]

    /**
     * Validates a MPEG-TS sync grid starting at [start]: checks for at least
     * 2 consecutive 188-stride 0x47 sync bytes within the next 1 KB, without
     * requiring the entire buffer to be MPEG-TS-aligned. Used to confirm a
     * video format boundary after a junk block.
     */
    private fun isMpegTsValidAt(data: ByteArray, start: Int): Boolean {
        if (start + MPEG_TS_PACKET_SIZE > data.size) return false
        if (data[start] != MPEG_TS_SYNC) return false
        var valid = 0
        var j = start
        val ceiling = minOf(data.size, start + 1024)
        while (j < ceiling) {
            if (j + MPEG_TS_PACKET_SIZE <= data.size && data[j] == MPEG_TS_SYNC) {
                valid++
            }
            j += MPEG_TS_PACKET_SIZE
        }
        return valid >= 2
    }

    /**
     * Merges overlapping or adjacent junk regions into a sorted, non-overlapping
     * list. Adjacent regions (the end of one is the start of the next) are
     * merged too — strips two back-to-back junk blocks as one continuous gap.
     */
    private fun mergeRegions(regions: List<IntRange>): List<IntRange> {
        if (regions.isEmpty()) return emptyList()
        val sorted = regions.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.first <= current.last + 1) {
                current = current.first..maxOf(current.last, next.last)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /**
     * Checks if it's a valid MPEG-TS stream
     */
    private fun isMpegTsValid(data: ByteArray): Boolean {
        if (data.size < MPEG_TS_PACKET_SIZE) return false

        // Check if the first byte is sync byte
        if (data[0] != MPEG_TS_SYNC) return false

        // Check if there are multiple sync bytes in correct locations
        var validPackets = 0
        for (i in 0 until minOf(data.size, 1024) step MPEG_TS_PACKET_SIZE) {
            if (i + MPEG_TS_PACKET_SIZE <= data.size && data[i] == MPEG_TS_SYNC) {
                validPackets++
            }
        }

        return validPackets >= 3
    }

    /**
     * Checks if it starts with JPEG header
     */
    private fun isJpegHeader(data: ByteArray): Boolean {
        if (data.size < 3) return false
        return data[0] == JPEG_HEADER[0] &&
            data[1] == JPEG_HEADER[1] &&
            data[2] == JPEG_HEADER[2]
    }

    /**
     * Checks if it starts with PNG header
     */
    private fun isPngHeader(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return data[0] == PNG_HEADER[0] &&
            data[1] == PNG_HEADER[1] &&
            data[2] == PNG_HEADER[2] &&
            data[3] == PNG_HEADER[3]
    }

    /**
     * Checks if it starts with GIF header
     */
    private fun isGifHeader(data: ByteArray): Boolean {
        if (data.size < 3) return false
        return data[0] == GIF_HEADER[0] &&
            data[1] == GIF_HEADER[1] &&
            data[2] == GIF_HEADER[2]
    }

    /**
     * Detects if a video is disguised under another format
     */
    private fun detectDisguise(data: ByteArray): Int {
        // Look for MP4 "ftyp" box
        val ftypOffset = findPattern(data, MP4_FTYP)
        if (ftypOffset >= 4) {
            return ftypOffset - 4 // "ftyp" is preceded by 4 bytes of size
        }

        // Look for AVI "RIFF"
        val riffOffset = findPattern(data, AVI_RIFF)
        if (riffOffset > 0) {
            return riffOffset
        }

        // Look for MPEG-TS sync byte
        val mpegTsOffset = findMpegTsSync(data)
        if (mpegTsOffset > 0) {
            return mpegTsOffset
        }

        return 0
    }

    /**
     * Checks if it's already a valid video format
     */
    private fun isVideoFormat(data: ByteArray): Boolean = isMpegTsValid(data) ||
        findPattern(data, MP4_FTYP) >= 0 ||
        findPattern(data, AVI_RIFF) >= 0

    /**
     * Finds a specific pattern in the data
     */
    private fun findPattern(data: ByteArray, pattern: ByteArray): Int {
        for (i in 0..data.size - pattern.size) {
            var found = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    found = false
                    break
                }
            }
            if (found) {
                return i
            }
        }
        return -1
    }

    /**
     * Finds the first MPEG-TS sync byte
     */
    private fun findMpegTsSync(data: ByteArray): Int {
        for (i in data.indices) {
            if (data[i] == MPEG_TS_SYNC) {
                // Check if there's a pattern of sync bytes
                var validCount = 0
                for (j in i until minOf(data.size, i + 1024) step MPEG_TS_PACKET_SIZE) {
                    if (j + MPEG_TS_PACKET_SIZE <= data.size && data[j] == MPEG_TS_SYNC) {
                        validCount++
                    }
                }
                if (validCount >= 2) {
                    return i
                }
            }
        }
        return -1
    }
}
