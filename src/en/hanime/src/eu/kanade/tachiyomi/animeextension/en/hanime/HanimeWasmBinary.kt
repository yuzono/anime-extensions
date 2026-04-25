package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// ---------------------------------------------------------------------------
// WASM Binary Extractor — Fetches and decodes the inline WASM from vendor.js
// ---------------------------------------------------------------------------
// hanime.tv embeds its signature WASM as a base64-encoded string inside the
// vendor.js bundle. This object discovers the vendor.js URL from the homepage,
// fetches it, scans for base64 blobs, and returns the one that decodes to a
// valid WASM binary (identified by the magic number \\0asm).
//
// Design: "Parse, Don't Validate" — each base64 candidate is decoded at the
// boundary and checked against the WASM magic number. Only a confirmed match
// is returned; everything else is discarded. Callers receive a trusted byte
// array that is guaranteed to be a valid WASM module header.
// ---------------------------------------------------------------------------

/**
 * Extracts the WASM binary from hanime.tv's vendor.js JavaScript bundle.
 *
 * The WASM binary is NOT fetched from a URL — it is base64-encoded inline
 * in the vendor.js file and decoded at runtime by the site's JavaScript
 * via `WebAssembly.compile(Uint8Array.from(atob("…")))` or similar patterns.
 *
 * This object replicates that extraction in Kotlin: it fetches the homepage
 * to discover the current vendor.js URL, downloads the JS bundle, scans for
 * base64 strings, and returns the one whose decoded bytes start with the
 * WASM magic number (`\0asm`).
 */
object HanimeWasmBinary {

    /** The WASM magic number: `\0asm` (0x00 0x61 0x73 0x6D). */
    private val WASM_MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6D)

    /** hanime.tv homepage — used to discover the vendor.js script URL. */
    private const val HANIME_HOME = "https://hanime.tv"

    /**
     * Fetch and extract the WASM binary from hanime.tv's vendor.js bundle.
     *
     * The process is:
     * 1. Fetch the hanime.tv homepage HTML.
     * 2. Extract the vendor.js URL from `<script>` tags.
     * 3. Fetch the vendor.js content.
     * 4. Scan for base64 strings and return the one that decodes to a WASM binary.
     *
     * @param client OkHttp client to use for HTTP requests.
     * @return The raw WASM binary bytes.
     * @throws WasmExtractionException if the binary cannot be extracted.
     */
    suspend fun fetchWasmBinary(client: OkHttpClient): ByteArray = withContext(Dispatchers.IO) {
        val html = fetchPage(client, HANIME_HOME)

        val vendorJsUrl = extractVendorJsUrl(html)
            ?: throw WasmExtractionException("Could not find vendor.js URL in hanime.tv HTML")

        val vendorJs = fetchPage(client, vendorJsUrl)

        extractWasmFromVendorJs(vendorJs)
    }

    /**
     * Extract the WASM binary from a vendor.js string.
     *
     * Scans for base64-encoded strings (minimum 100 characters to filter noise),
     * decodes each candidate, and checks whether the decoded bytes start with the
     * WASM magic number (`\0asm`). Returns the first match.
     *
     * Recognised JavaScript patterns:
     * - `WebAssembly.compile(Uint8Array.from(atob("AGFzbQE…")))`
     * - `new Uint8Array(Base64.decode("AGFzbQE…"))`
     * - Any large quoted base64 string that decodes to valid WASM.
     *
     * @param vendorJs The full text content of the vendor.js bundle.
     * @return The decoded WASM binary bytes.
     * @throws WasmExtractionException if no WASM binary is found.
     */
    fun extractWasmFromVendorJs(vendorJs: String): ByteArray {
        val base64Pattern = Regex("""["']([A-Za-z0-9+/=]{100,})["']""")

        val matches = base64Pattern.findAll(vendorJs).toList()

        if (matches.isEmpty()) {
            throw WasmExtractionException("No base64 strings found in vendor.js")
        }

        for (match in matches) {
            val base64Str = match.groupValues[1]

            val decoded = try {
                decodeBase64(base64Str)
            } catch (_: Exception) {
                // Not valid base64 — skip
                continue
            }

            if (decoded.size >= WASM_MAGIC.size && decoded.sliceArray(0 until WASM_MAGIC.size).contentEquals(WASM_MAGIC)) {
                return decoded
            }
        }

        throw WasmExtractionException("Could not find WASM binary in vendor.js")
    }

    /**
     * Extract the vendor.js URL from the hanime.tv HTML.
     *
     * Looks for `<script src="…vendor….js">` tags and returns the matching URL.
     * Relative paths are resolved against `https://hanime.tv`.
     *
     * @param html The hanime.tv homepage HTML.
     * @return The fully-qualified vendor.js URL, or `null` if not found.
     */
    fun extractVendorJsUrl(html: String): String? {
        val scriptPattern = Regex("""src=["']([^"']*vendor[^"']*\.js)["']""")
        val match = scriptPattern.find(html) ?: return null

        val path = match.groupValues[1]
        return if (path.startsWith("http")) {
            path
        } else {
            try {
                HANIME_HOME.toHttpUrl().resolve(path)?.toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Fetch a page's text content via HTTP GET.
     *
     * @param client OkHttp client to use.
     * @param url The URL to fetch.
     * @return The response body as a string.
     * @throws WasmExtractionException on HTTP failure or empty body.
     */
    private fun fetchPage(client: OkHttpClient, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/javascript,*/*")
            .build()

        val response = client.newCall(request).execute()

        return response.use {
            if (!it.isSuccessful) {
                throw WasmExtractionException("HTTP ${it.code} fetching $url")
            }
            it.body.string()
                ?: throw WasmExtractionException("Empty response body for $url")
        }
    }

    /**
     * Thrown when the WASM binary cannot be extracted from the vendor.js bundle.
     */
    class WasmExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
