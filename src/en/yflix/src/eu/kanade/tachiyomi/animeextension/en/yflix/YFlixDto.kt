package eu.kanade.tachiyomi.animeextension.en.yflix

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// ============================== Generic AJAX Response ==============================

@Serializable
data class ResultResponse(
    val result: String,
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}

// ============================== Decryption Responses ==============================

@Serializable
data class DecryptedIframeResponse(
    val result: DecryptedUrl,
)

@Serializable
data class DecryptedUrl(
    val url: String,
)
