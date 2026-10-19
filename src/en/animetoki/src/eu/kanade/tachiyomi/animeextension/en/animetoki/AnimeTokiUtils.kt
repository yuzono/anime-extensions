package eu.kanade.tachiyomi.animeextension.en.animetoki

fun naturalCompare(a: String, b: String): Int {
    var ia = 0
    var ib = 0
    while (ia < a.length && ib < b.length) {
        val charA = a[ia]
        val charB = b[ib]
        if (charA.isDigit() && charB.isDigit()) {
            val startA = ia
            while (ia < a.length && a[ia].isDigit()) ia++
            val startB = ib
            while (ib < b.length && b[ib].isDigit()) ib++
            val numA = a.substring(startA, ia).toLongOrNull() ?: 0L
            val numB = b.substring(startB, ib).toLongOrNull() ?: 0L
            val cmp = numA.compareTo(numB)
            if (cmp != 0) return cmp

            val lenCmp = (ia - startA).compareTo(ib - startB)
            if (lenCmp != 0) return lenCmp
        } else {
            val cmp = charA.lowercaseChar().compareTo(charB.lowercaseChar())
            if (cmp != 0) return cmp
            ia++
            ib++
        }
    }
    return a.length.compareTo(b.length)
}
