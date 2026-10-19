package eu.kanade.tachiyomi.animeextension.en.hexawatch

/**
 * Reads the coded frame size from the first H.264 sequence parameter set (SPS) found in [data],
 * e.g. the start of an MPEG-TS segment. Returns `null` if no SPS is found or it is truncated.
 */
object H264Resolution {

    fun parse(data: ByteArray): Pair<Int, Int>? {
        val start = findSps(data) ?: return null
        return try {
            readSps(BitReader(unescape(data, start)))
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    /** Index of the first byte after the SPS NAL header, or `null`. */
    private fun findSps(data: ByteArray): Int? {
        for (i in 0 until data.size - 3) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1 &&
                data[i + 3].toInt() and 0x1f == NAL_SPS
            ) {
                return i + 4
            }
        }
        return null
    }

    /** Strips emulation prevention bytes (00 00 03 -> 00 00) from the NAL payload. */
    private fun unescape(data: ByteArray, start: Int): ByteArray {
        val end = minOf(data.size, start + MAX_SPS_SIZE)
        val out = ArrayList<Byte>(end - start)
        var zeros = 0
        for (i in start until end) {
            val b = data[i]
            if (zeros >= 2 && b.toInt() == 3) {
                zeros = 0
                continue
            }
            out.add(b)
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun readSps(r: BitReader): Pair<Int, Int> {
        val profile = r.bits(8)
        r.bits(16) // constraint flags + level
        r.ue() // seq_parameter_set_id

        var chromaFormat = 1
        if (profile in HIGH_PROFILES) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) r.bit() // separate_colour_plane_flag
            r.ue() // bit_depth_luma_minus8
            r.ue() // bit_depth_chroma_minus8
            r.bit() // qpprime_y_zero_transform_bypass_flag
            if (r.bit() == 1) { // seq_scaling_matrix_present_flag
                repeat(if (chromaFormat == 3) 12 else 8) { i ->
                    if (r.bit() == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }

        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.bit()
                r.se()
                r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.bit() // gaps_in_frame_num_value_allowed_flag

        var width = (r.ue() + 1) * 16
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bit()
        var height = (2 - frameMbsOnly) * heightMapUnits * 16
        if (frameMbsOnly == 0) r.bit() // mb_adaptive_frame_field_flag
        r.bit() // direct_8x8_inference_flag

        if (r.bit() == 1) { // frame_cropping_flag
            val cropX = if (chromaFormat == 1 || chromaFormat == 2) 2 else 1
            val cropY = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
            width -= cropX * (r.ue() + r.ue())
            height -= cropY * (r.ue() + r.ue())
        }
        return width to height
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        repeat(size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            if (next != 0) last = next
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var pos = 0

        fun bit(): Int = (data[pos shr 3].toInt() shr (7 - (pos++ and 7))) and 1

        fun bits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bit() }
            return v
        }

        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) zeros++
            return (1 shl zeros) - 1 + bits(zeros)
        }

        fun se(): Int {
            val v = ue()
            return if (v % 2 == 1) (v + 1) / 2 else -(v / 2)
        }
    }

    private const val NAL_SPS = 7
    private const val MAX_SPS_SIZE = 256
    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
}
