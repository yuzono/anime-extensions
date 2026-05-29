package eu.kanade.tachiyomi.animeextension.all.stremio

import java.net.URLEncoder

fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")

fun String.takeNotBlank(): String? = this.takeIf { it.isNotBlank() }
