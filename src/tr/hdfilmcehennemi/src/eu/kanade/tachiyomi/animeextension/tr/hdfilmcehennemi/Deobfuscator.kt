package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi

import android.util.Base64

object Deobfuscator {
    val partsRegex by lazy { Regex("""[\w_]+\s*=\s*[\w_]+\(\[(.*?)]\)""") }

    fun rot13ReverseUnmix(valueParts: Array<String>): String {
        val value = valueParts.joinToString("") { it.trim().trim('"') }
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
}
