package eu.kanade.tachiyomi.animeextension.en.shuttletv

import keiyoushi.utils.jsonInstance
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.io.encoding.Base64

/**
 * Kotlin port of the cinesrc challenge solvers
 * (github.com/smy778/EncDecEndpoints – samples/challenge/cinesrc.py).
 *
 * Stage 1 is a CSP4 proof-of-work: parse the 32-byte challenge packed in
 * `w`, build its S-box, then brute-force a nonce until
 * sha256(mix-output || challenge || nonce || config || seed) has `diff`
 * leading zero bits. All arithmetic is 32-bit signed (JS imul semantics),
 * which Kotlin's Int provides natively.
 *
 * Stage 2 is a plain hash brute force over a difficulty encoded in the
 * reversed JWE-looking blob at `pack[4]`.
 */
internal object CinesrcChallenge {

    // ---- CSP4 constants (stage 1) ----

    private val K = intArrayOf(
        0x46C6C721.toInt(), 0x4230C311.toInt(), 0x18223A1D.toInt(), 0x56389BA7.toInt(),
        0xD4A87103.toInt(), 0x07CB9E2F.toInt(), 0x73085F9D.toInt(), 0xD2396A1F.toInt(),
        0x23AE5ADD.toInt(), 0x4646C48F.toInt(), 0x0F4E0903.toInt(), 0x808F5F31.toInt(),
        0x953D4E07.toInt(), 0x7E238FFF.toInt(), 0x634595DF.toInt(), 0xCB5E4301.toInt(),
    )

    private val PERMUTATIONS = arrayOf(
        intArrayOf(3, 5, 1, 0, 2, 4),
        intArrayOf(2, 0, 4, 5, 3, 1),
        intArrayOf(1, 4, 5, 3, 0, 2),
        intArrayOf(2, 0, 1, 5, 4, 3),
        intArrayOf(4, 2, 1, 0, 3, 5),
        intArrayOf(5, 0, 2, 1, 3, 4),
        intArrayOf(4, 5, 1, 0, 3, 2),
        intArrayOf(4, 0, 3, 5, 1, 2),
    )

    private val SCHEDULES = arrayOf(
        intArrayOf(0, 7, 6, 3, 2, 1, 5, 4, 0, 2, 4, 6, 5, 1, 7, 3),
        intArrayOf(5, 2, 1, 3, 0, 4, 6, 7, 4, 1, 0, 7, 3, 6, 5, 2),
        intArrayOf(4, 0, 2, 1, 5, 7, 6, 3, 2, 6, 0, 7, 4, 3, 5, 1),
        intArrayOf(5, 4, 7, 0, 2, 3, 1, 6, 5, 1, 4, 0, 7, 2, 3, 6),
        intArrayOf(4, 7, 0, 2, 1, 6, 5, 3, 6, 1, 3, 2, 0, 7, 4, 5),
        intArrayOf(0, 7, 2, 6, 3, 4, 5, 1, 6, 5, 0, 2, 7, 1, 4, 3),
        intArrayOf(0, 4, 5, 3, 1, 7, 2, 6, 3, 7, 5, 6, 1, 0, 2, 4),
        intArrayOf(7, 6, 0, 4, 2, 5, 1, 3, 7, 5, 3, 4, 2, 6, 0, 1),
    )

    // PROFILES: mode -> (rounds, m_b). Only structural sanity is needed here,
    // the values are used as-is from the parsed challenge.
    private val PROFILES = mapOf(0 to (12 to 15), 1 to (16 to 17))

    // ---- Stage 2 ----

    fun solveStage2(data: Stage2ChallengeDto, ensureActive: () -> Unit = {}): String {
        val pack = data.pack
        val target = pack[0].jsonPrimitive.content.reversed()
        val salt = pack[3].jsonPrimitive.content.reversed()
        val blob = pack[4].jsonPrimitive.content.reversed()

        fun decode(s: String): String = Base64.UrlSafe.decode(s + "=".repeat((4 - s.length % 4) % 4)).decodeToString()

        val body = decode(blob.split(".")[1])
        val payload = decode(body.split(".", limit = 2)[1])
        val difficulty = jsonInstance.parseToJsonElement(payload)
            .jsonObject["d"]!!.jsonPrimitive.content.toInt()
        val width = (difficulty + 3) / 4

        val digest = MessageDigest.getInstance("SHA-256")
        for (counter in 0 until (1 shl difficulty)) {
            if (counter and 0x3FF == 0) ensureActive()
            val key = counter.toString(16).padStart(width, '0')
            val hash = digest.digest((salt + key).toByteArray()).toHexString()
            if (hash == target) return key
        }
        throw IllegalStateException("cinesrc stage2: no solution found")
    }

    // ---- Stage 1 ----

    fun solveStage1(data: Stage1ChallengeDto, seed: String, ensureActive: () -> Unit = {}): String {
        val seedBytes = seed.hexToByteArray()
        val challenge = parseChallenge(data.w)
        val sBox = buildSBox(challenge.w, challenge.varr, challenge.sI, challenge.mB)

        val msg = ByteArray(88)
        System.arraycopy(challenge.raw, 0, msg, 24, 16)
        val cfg = challenge.mB xor 0x6B29
        msg[48] = 4
        msg[49] = challenge.mode.toByte()
        msg[50] = challenge.varr.toByte()
        msg[51] = challenge.sI.toByte()
        msg[52] = challenge.pI.toByte()
        msg[53] = challenge.rnd.toByte()
        msg[54] = (cfg and 0xFF).toByte()
        msg[55] = (cfg shr 8).toByte()
        System.arraycopy(seedBytes, 0, msg, 56, 32)

        val sha = MessageDigest.getInstance("SHA-256")
        var n = 0L
        while (true) {
            if (n and 0x3FF == 0L) ensureActive()
            val m = mix(
                challenge.w,
                sBox,
                challenge.varr,
                challenge.rnd,
                challenge.mB,
                challenge.sI,
                challenge.pI,
                n,
            )
            for (i in 0 until 6) writeIntLE(msg, i * 4, m[i])
            writeLongLE(msg, 40, n)

            if (hasLeadingZeroBits(sha.digest(msg), challenge.diff)) {
                return "m4." + n.toString(16)
            }
            n++
        }
    }

    private class Challenge(
        val raw: ByteArray,
        val w: IntArray,
        val mode: Int,
        val diff: Int,
        val varr: Int,
        val rnd: Int,
        val mB: Int,
        val sI: Int,
        val pI: Int,
    )

    private fun parseChallenge(w: String): Challenge {
        val c = Base64.UrlSafe.decode(w + "=".repeat((4 - w.length % 4) % 4))

        require(c.size == 32) { "invalid CSP4 challenge length" }
        require(
            c[16] == 0x7D.toByte() && c[17] == 0xBD.toByte() &&
                c[18] == 0xD2.toByte() && c[19] == 0x43.toByte(),
        ) { "invalid CSP4 magic" }
        require(c[20].toInt() == 4) { "invalid CSP4 version" }
        require(
            c[28] == 'C'.code.toByte() && c[29] == 'S'.code.toByte() &&
                c[30] == 'P'.code.toByte() && c[31] == '4'.code.toByte(),
        ) { "invalid CSP4 tag" }

        val mode = c[21].toInt() and 0xFF
        val diff = c[22].toInt() and 0xFF
        val varr = c[23].toInt() and 0xFF
        val rnd = c[24].toInt() and 0xFF
        val mB = c[25].toInt() and 0xFF
        val sI = c[26].toInt() and 0xFF
        val pI = c[27].toInt() and 0xFF

        require(diff in 1..30) { "invalid difficulty" }
        require(varr <= 7 && sI <= 7 && pI <= 7) { "invalid CSP4 selector" }
        require(PROFILES[mode] == (rnd to mB)) { "invalid CSP4 profile" }

        val wInts = IntArray(4)
        for (i in 0 until 4) {
            wInts[i] = readIntLE(c, i * 4)
        }
        return Challenge(c, wInts, mode, diff, varr, rnd, mB, sI, pI)
    }

    private fun buildSBox(w: IntArray, varr: Int, sI: Int, mB: Int): IntArray {
        val size = 1 shl mB
        val s = IntArray(size)

        var x = w[varr and 3] xor K[sI] xor (K[8 + sI] * (varr + 1))
        var y = w[(varr + 1) and 3] xor K[15 - sI]

        for (i in 0 until size) {
            x += w[(i + sI) and 3] + (K[(i + varr) and 15] * (i + 1))
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)

            y = rotl((K[(i + 9) and 15] * (i + 3)) xor y xor x, i + varr + sI)

            s[i] = (K[(i + 5) and 15] * (i xor varr)) xor
                rotl(w[(i + varr) and 3], i + sI) xor x xor y
        }

        return s
    }

    private fun mix(
        w: IntArray,
        s: IntArray,
        varr: Int,
        rnd: Int,
        mB: Int,
        sI: Int,
        pI: Int,
        n: Long,
    ): IntArray {
        val mask = (1 shl mB) - 1
        val lo = n.toInt()
        val hi = (n ushr 32).toInt()

        var a = w[0] xor K[varr] xor lo
        var b = rotl(lo, varr + 7) xor w[2]
        var c = w[1] xor K[8 + sI] xor hi
        var d = rotl(lo xor hi, sI + 11) xor w[3]
        var e = K[sI + varr] xor lo xor hi
        var f = K[15 - varr] xor w[sI and 3]

        val sch = SCHEDULES[sI]

        for (r in 0 until rnd) {
            val i1 = (rotl(b, r + 1) xor (K[r and 15] * f) xor e xor a) and mask
            val v1 = s[i1]

            val i2 = (rotl(e, varr + r + 3) xor (K[(r + 7) and 15] * d) xor c xor v1) and mask
            val op = sch[r]

            when (op) {
                0 -> {
                    a = rotl(a + d + v1, 5)
                    c = (c xor f xor a) * 1187432225 + b
                }
                1 -> {
                    b = rotl(c xor e xor b xor v1, 11)
                    d += (b xor a) * 1110491921
                }
                2 -> {
                    c = rotl(c + b + v1, 17)
                    a = (d xor f xor c) * 404896285 xor a
                }
                3 -> {
                    d = rotl(a xor d xor v1, 23)
                    b += (c xor e xor d) * 1446550439
                }
                4 -> {
                    a = (a xor e xor v1) * (-727158525) + rotl(b, 7)
                    d = rotl(c + f + a, 13) xor d
                }
                5 -> {
                    c = (c + f + v1) * 130784815 xor rotl(d, 9)
                    b += rotl(a xor e xor c, 19)
                }
                6 -> {
                    b = (b xor f xor v1) * 1929928605 + rotl(a, 3)
                    c = rotl(d + e + b, 15) xor c
                }
                else -> {
                    d = (d + e + v1) * (-767989217) xor rotl(c, 21)
                    a += rotl(b xor f xor d, 27)
                }
            }

            val v2 = s[i2]

            if ((f xor r xor v1) and 1 == 0) {
                f = rotl(v2 + f + a, r + 5)
                e = K[8 + op] * (v2 xor e xor f) + d
            } else {
                e = rotl(v2 xor e xor b, r + 9)
                f = (K[(op + 11) and 15] * (v2 + f + e)) xor c
            }
        }

        val v = intArrayOf(a, c, b, d, f, e)
        val p = PERMUTATIONS[pI]
        return intArrayOf(v[p[0]], v[p[1]], v[p[2]], v[p[3]], v[p[4]], v[p[5]])
    }

    private fun hasLeadingZeroBits(hash: ByteArray, bits: Int): Boolean {
        val full = bits / 8
        val rest = bits % 8
        for (i in 0 until full) {
            if (hash[i].toInt() != 0) return false
        }
        return rest == 0 ||
            (hash[full].toInt() and 0xFF) ushr (8 - rest) == 0
    }

    // JS Math.imul semantics == Kotlin Int multiplication (wrapping).
    private fun rotl(x: Int, n: Int): Int {
        val s = n and 31
        return if (s == 0) x else (x shl s) or (x ushr (32 - s))
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeIntLE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeLongLE(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            target[offset + i] = ((value ushr (i * 8)) and 0xFF).toByte()
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
