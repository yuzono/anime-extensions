package eu.kanade.tachiyomi.animeextension.en.movhub

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

// For Extractor: represents the initial encrypted response from rapidshare.cc/media/
@Serializable
data class EncryptedRapidResponse(
    val result: String,
)

// For Extractor: represents the final decrypted response from enc-dec.app
@Serializable
data class RapidDecryptResponse(
    val status: Int,
    val result: RapidShareResult,
)

@Serializable
data class RapidShareResult(
    val sources: List<RapidShareSource> = emptyList(),
    val tracks: List<RapidShareTrack> = emptyList(),
)

@Serializable
data class RapidShareSource(
    val file: String,
)

@Serializable
data class RapidShareTrack(
    val file: String,
    val label: String? = null,
    val kind: String,
)
