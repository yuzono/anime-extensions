package eu.kanade.tachiyomi.util

import okhttp3.Response
import org.jsoup.nodes.Document

/**
 * Returns a Jsoup document for this response.
 * @param html the body of the response. Use only if the body was read before calling this method.
 */
@Suppress("unused_parameter")
fun Response.asJsoup(html: String? = null): Document = throw Exception("Stub!")
