package keiyoushi.lib.jsunpacker

import kotlin.math.pow

internal data class Unbaser(
    private val base: Int,
) {
    private val selector: Int = when {
        base > 62 -> 95
        base > 54 -> 62
        base > 52 -> 54
        else -> 52
    }

    private val dict by lazy {
        if (base in 2..36) {
            null
        } else {
            ALPHABET[selector]?.let { str -> str.indices.associateBy { str[it] } }
        }
    }

    fun unbase(value: String): Int = if (base in 2..36) {
        value.toIntOrNull(base) ?: 0
    } else {
        var returnVal = 0

        val valArray = value.toCharArray().reversed()
        for (i in valArray.indices) {
            val cipher = valArray[i]
            returnVal += (base.toFloat().pow(i) * (dict?.get(cipher) ?: 0)).toInt()
        }
        returnVal
    }

    companion object {
        private val ALPHABET = mapOf(
            52 to "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP",
            54 to "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQR",
            62 to "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
            95 to " !\"#$%&\\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~",
        )
    }
}
