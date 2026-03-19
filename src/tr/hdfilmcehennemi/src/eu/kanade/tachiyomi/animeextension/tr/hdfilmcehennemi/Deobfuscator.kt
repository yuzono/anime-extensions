package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi

import android.util.Base64
import kotlin.collections.forEachIndexed

object Deobfuscator {
    val partsRegex by lazy { Regex("""[\w_]+\s*=\s*[\w_]+\(\[(.*?)]\)""") }

    fun rot13ReverseBase64Unmix(valueParts: Array<String>): String {
        val value = valueParts.joinToString("") { it.trim().trim('"') }
        return rot13ReverseBase64Unmix(value)
    }

    fun rot13ReverseBase64Unmix(value: String): String {
        var result = value

        // ROT13
        result = result.map { c ->
            if (c.isLetter()) {
                val base = if (c.isUpperCase()) 'A' else 'a'
                ((c - base + 13) % 26 + base.code).toChar()
            } else {
                c
            }
        }.joinToString("")

        // reverse
        result = result.reversed()

        // base64 decode
        val decoded = Base64.decode(result, Base64.DEFAULT)

        // unmix characters
        val sb = StringBuilder()
        for (i in decoded.indices) {
            var charCode = decoded[i].toInt()
            charCode = (charCode - (399756995 % (i + 5)) + 256) % 256
            sb.append(charCode.toChar())
        }

        return sb.toString()
    }

    fun base64Rot13ReverseUnmix(valueParts: Array<String>): String {
        val value = valueParts.joinToString("") { it.trim().trim('"') }
        return base64Rot13ReverseUnmix(value)
    }

    fun base64Rot13ReverseUnmix(value: String): String {
        // base64 decode
        var decoded = Base64.decode(value, Base64.DEFAULT)

        // ROT13
        decoded.forEachIndexed { index, b ->
            val c = (b.toInt() and 0xFF).toChar()
            if (c.isAlphabet()) {
                val base = if (c.isUpperCase()) 'A' else 'a'
                decoded[index] = ((c - base + 13) % 26 + base.code).toByte()
            }
        }

        // reverse
        decoded = decoded.reversed().toByteArray()

        // unmix characters
        val sb = StringBuilder()
        for (i in decoded.indices) {
            var charCode = decoded[i].toInt()
            charCode = (charCode - (399756995 % (i + 5)) + 256) % 256
            sb.append(charCode.toChar())
        }

        return sb.toString()
    }
}

fun Char.isAlphabet(): Boolean {
    val code = this.code
    return code in 65..90 || code in 97..122
}
