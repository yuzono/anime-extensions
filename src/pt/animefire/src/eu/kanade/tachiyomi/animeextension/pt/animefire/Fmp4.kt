package eu.kanade.tachiyomi.animeextension.pt.animefire

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Splits a muxed fragmented MP4 into one stream per track.
 *
 * FFmpeg's DASH demuxer reads every representation as a single track, so a representation that
 * carries both audio and video loses all of its audio. Each representation therefore needs its own
 * initialization segment and fragments that only contain the samples of that track.
 */
internal object Fmp4 {
    enum class Kind(
        val handler: String,
    ) {
        VIDEO("vide"),
        AUDIO("soun"),
    }

    class Init(
        val trackIds: Map<Kind, Int>,
        val defaultSizes: Map<Int, Int>,
        private val data: ByteArray,
    ) {
        fun forTrack(kind: Kind): ByteArray = Fmp4.forTrack(data, kind, trackIds.getValue(kind))
    }

    private class Box(
        val type: String,
        val start: Int,
        val end: Int,
        val header: Int,
    ) {
        val body get() = start + header
    }

    private class Reader(
        val data: ByteArray,
    ) {
        val buf: ByteBuffer = ByteBuffer.wrap(data)

        fun boxes(
            from: Int,
            to: Int,
        ): List<Box> {
            val boxes = mutableListOf<Box>()
            var pos = from
            while (pos + 8 <= to) {
                var size = buf.getInt(pos).toLong() and 0xffffffffL
                var header = 8
                if (size == 1L) {
                    size = buf.getLong(pos + 8)
                    header = 16
                } else if (size == 0L) {
                    size = (to - pos).toLong()
                }
                if (size < header || pos + size > to) throw IOException("Invalid MP4 box")
                boxes += Box(String(data, pos + 4, 4, Charsets.ISO_8859_1), pos, pos + size.toInt(), header)
                pos += size.toInt()
            }
            return boxes
        }

        fun children(box: Box) = boxes(box.body, box.end)

        fun find(
            from: Int,
            to: Int,
            type: String,
        ) = boxes(from, to).firstOrNull { it.type == type }

        fun bytes(box: Box) = data.copyOfRange(box.start, box.end)
    }

    private fun box(
        type: String,
        vararg parts: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).putInt(8 + parts.sumOf { it.size }).array())
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        parts.forEach(out::write)
        return out.toByteArray()
    }

    fun parseInit(data: ByteArray): Init {
        val reader = Reader(data)
        val moov = reader.find(0, data.size, "moov") ?: throw IOException("Missing moov")
        val trackIds = linkedMapOf<Kind, Int>()
        for (trak in reader.children(moov).filter { it.type == "trak" }) {
            val handler = trak.handler(reader) ?: continue
            Kind.entries.firstOrNull { it.handler == handler }?.let { trackIds.putIfAbsent(it, trak.trackId(reader)) }
        }
        val defaultSizes =
            reader
                .find(moov.body, moov.end, "mvex")
                ?.let { reader.children(it) }
                .orEmpty()
                .filter { it.type == "trex" }
                .associate { reader.buf.getInt(it.body + 4) to reader.buf.getInt(it.body + 16) }
        if (Kind.VIDEO !in trackIds) throw IOException("Missing video track")
        return Init(trackIds.toSortedMap(compareBy { it.ordinal }), defaultSizes, data)
    }

    private fun Box.trackId(reader: Reader): Int {
        val tkhd = reader.find(body, end, "tkhd") ?: throw IOException("Missing tkhd")
        return reader.buf.getInt(tkhd.body + if (reader.data[tkhd.body].toInt() == 1) 20 else 12)
    }

    private fun Box.handler(reader: Reader): String? {
        val mdia = reader.find(body, end, "mdia") ?: return null
        val hdlr = reader.find(mdia.body, mdia.end, "hdlr") ?: return null
        return String(reader.data, hdlr.body + 8, 4, Charsets.ISO_8859_1)
    }

    private fun forTrack(
        data: ByteArray,
        kind: Kind,
        trackId: Int,
    ): ByteArray {
        val reader = Reader(data)
        val out = ByteArrayOutputStream()
        for (top in reader.boxes(0, data.size)) {
            when (top.type) {
                "ftyp" -> {
                    out.write(reader.bytes(top))
                }
                "moov" -> {
                    out.write(
                        box(
                            "moov",
                            *reader
                                .children(top)
                                .mapNotNull { child ->
                                    when (child.type) {
                                        "trak" -> {
                                            reader.bytes(child).takeIf { child.handler(reader) == kind.handler }
                                        }
                                        "mvex" -> {
                                            box(
                                                "mvex",
                                                *reader
                                                    .children(child)
                                                    .filter { it.type != "trex" || reader.buf.getInt(it.body + 4) == trackId }
                                                    .map(reader::bytes)
                                                    .toTypedArray(),
                                            )
                                        }
                                        else -> {
                                            reader.bytes(child)
                                        }
                                    }
                                }.toTypedArray(),
                        ),
                    )
                }
            }
        }
        return out.toByteArray()
    }

    /** Keeps only the samples of [trackId] in every fragment of [data]. */
    fun fragments(
        data: ByteArray,
        trackId: Int,
        defaultSizes: Map<Int, Int>,
    ): ByteArray {
        val reader = Reader(data)
        val top = reader.boxes(0, data.size)
        val out = ByteArrayOutputStream()
        top.forEachIndexed { index, box ->
            when (box.type) {
                "styp" -> {
                    out.write(reader.bytes(box))
                }
                "moof" -> {
                    val mdat = top.getOrNull(index + 1)?.takeIf { it.type == "mdat" } ?: throw IOException("Missing mdat")
                    fragment(reader, box, mdat, trackId, defaultSizes)?.let(out::write)
                }
            }
        }
        return out.toByteArray()
    }

    private fun fragment(
        reader: Reader,
        moof: Box,
        mdat: Box,
        trackId: Int,
        defaultSizes: Map<Int, Int>,
    ): ByteArray? {
        val buf = reader.buf
        val children = reader.children(moof)
        val traf =
            children.filter { it.type == "traf" }.firstOrNull { traf ->
                reader.find(traf.body, traf.end, "tfhd")?.let { buf.getInt(it.body + 4) == trackId } == true
            } ?: return null
        val parts = reader.children(traf)
        val tfhd = parts.first { it.type == "tfhd" }
        val tfhdFlags = buf.getInt(tfhd.body) and 0xffffff
        // CMAF fragments address samples relative to the moof, which is the only layout handled here.
        if (tfhdFlags and 0x1 != 0) throw IOException("Unsupported base data offset")
        val defaultSize =
            if (tfhdFlags and 0x10 != 0) {
                var skip = 8
                if (tfhdFlags and 0x2 != 0) skip += 4
                if (tfhdFlags and 0x8 != 0) skip += 4
                buf.getInt(tfhd.body + skip)
            } else {
                defaultSizes[trackId]
            }
        val newTfhd =
            box(
                "tfhd",
                ByteBuffer.allocate(4).putInt((tfhdFlags or 0x20000)).array(),
                reader.data.copyOfRange(tfhd.body + 4, tfhd.end),
            )

        class Run(
            val box: Box,
            val flags: Int,
            val payload: ByteArray,
        )

        val runs = mutableListOf<Run>()
        var previousEnd = -1
        for (trun in parts.filter { it.type == "trun" }) {
            val flags = buf.getInt(trun.body) and 0xffffff
            val count = buf.getInt(trun.body + 4)
            var pos = trun.body + 8
            val start =
                if (flags and 0x1 != 0) {
                    moof.start + buf.getInt(pos).also { pos += 4 }
                } else {
                    previousEnd.also { if (it < 0) throw IOException("Missing trun data offset") }
                }
            if (flags and 0x4 != 0) pos += 4
            val stride = listOf(0x100, 0x200, 0x400, 0x800).count { flags and it != 0 } * 4
            var size = 0
            repeat(count) { sample ->
                size +=
                    if (flags and 0x200 != 0) {
                        var offset = pos + sample * stride
                        if (flags and 0x100 != 0) offset += 4
                        buf.getInt(offset)
                    } else {
                        defaultSize ?: throw IOException("Missing sample size")
                    }
            }
            if (start < mdat.body || start + size > mdat.end) throw IOException("Sample data outside mdat")
            previousEnd = start + size
            runs += Run(trun, flags, reader.data.copyOfRange(start, start + size))
        }

        val newRuns =
            runs.map { run ->
                val trun = run.box
                val hasOffset = run.flags and 0x1 != 0
                // flags (4) + sample count (4), a data offset (4) added if missing, then the rest unchanged
                val rest = reader.data.copyOfRange(trun.body + 8 + if (hasOffset) 4 else 0, trun.end)
                run to rest
            }
        val tfdt = parts.firstOrNull { it.type == "tfdt" }?.let(reader::bytes) ?: ByteArray(0)
        val mfhd = children.first { it.type == "mfhd" }.let(reader::bytes)
        val trafSize = 8 + newTfhd.size + tfdt.size + newRuns.sumOf { (_, rest) -> 8 + 12 + rest.size }
        val moofSize = 8 + mfhd.size + trafSize

        var offset = moofSize + 8
        val truns =
            newRuns.map { (run, rest) ->
                val trun = run.box
                val header =
                    ByteBuffer
                        .allocate(12)
                        .putInt(buf.getInt(trun.body) or 0x1)
                        .putInt(buf.getInt(trun.body + 4))
                        .putInt(offset)
                        .array()
                offset += run.payload.size
                box("trun", header, rest)
            }
        val newMoof = box("moof", mfhd, box("traf", newTfhd, tfdt, *truns.toTypedArray()))
        check(newMoof.size == moofSize)
        return newMoof + box("mdat", *runs.map { it.payload }.toTypedArray())
    }
}
