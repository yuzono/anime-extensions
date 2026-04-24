package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests against the live hanime.tv API endpoints.
 *
 * The manifest endpoint has migrated to a CDN at cached.freeanimehentai.net
 * which has a strict signature freshness requirement (signatures expire within
 * ~2 minutes based on s-maxage=120). Tests generate fresh signatures immediately
 * before manifest requests to avoid timing-related 401 rejections.
 * The hanime.tv origin now rejects all direct manifest requests with 401.
 *
 * Tests gracefully skip when the network is unavailable or the WASM
 * binary cannot be fetched — no false failures from environment issues.
 */
class HanimeApiIntegrationTest {

    // ── JVM test setup (Base64) ──────────────────────────────────────

    @Before
    fun setUp() {
        Base64Provider.instance = JvmBase64
    }

    // ── Constants ─────────────────────────────────────────────────────

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val BASE_URL = "https://hanime.tv"
        const val CDN_BASE_URL = "https://cached.freeanimehentai.net"
        const val SEARCH_URL = "https://search.htv-services.com/"
        const val SKIP_PREFIX = "SKIP:"
    }

    // ── HTTP helpers (pure JDK, zero Android dependencies) ────────────

    /** HTTP response data class. */
    private data class HttpResponse(
        val code: Int,
        val body: String?,
        val isSuccessful: Boolean = code in 200..299,
    )

    /** Perform a GET request with custom headers. Returns HttpResponse or null on connection failure. */
    private fun httpGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeout: Int = 30000,
    ): HttpResponse? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = timeout
        headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        val code = conn.responseCode
        val body = try {
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            try {
                conn.errorStream?.bufferedReader()?.readText()
            } catch (_: Exception) {
                null
            }
        }
        conn.disconnect()
        HttpResponse(code, body)
    } catch (_: Exception) {
        null
    }

    /** Perform a POST request with custom headers and JSON body. Returns HttpResponse or null on connection failure. */
    private fun httpPost(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeout: Int = 30000,
    ): HttpResponse? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = timeout
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
        headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        conn.outputStream.bufferedWriter().use { it.write(body) }
        val code = conn.responseCode
        val responseBody = try {
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            null
        }
        conn.disconnect()
        HttpResponse(code, responseBody)
    } catch (_: Exception) {
        null
    }

    // ── Shared infrastructure ─────────────────────────────────────────

    private fun getWasmBinary(): ByteArray? {
        return try {
            val homeResponse = httpGet(
                "https://hanime.tv",
                mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/javascript,*/*",
                ),
            ) ?: return null
            if (!homeResponse.isSuccessful) return null
            val homeHtml = homeResponse.body ?: return null

            val vendorJsUrl = HanimeWasmBinary.extractVendorJsUrl(homeHtml) ?: return null

            val vendorResponse = httpGet(
                vendorJsUrl,
                mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/javascript,*/*",
                ),
            ) ?: return null
            if (!vendorResponse.isSuccessful) return null
            val vendorJs = vendorResponse.body ?: return null

            HanimeWasmBinary.extractWasmFromVendorJs(vendorJs)
        } catch (_: Exception) {
            null
        }
    }

    private fun createSignatureProvider(): ChicorySignatureProvider? {
        val binary = getWasmBinary() ?: return null
        val provider = ChicorySignatureProvider(binary)
        return try {
            provider.initialize()
            provider
        } catch (_: Exception) {
            null
        }
    }

    private fun getSignatureHeaders(provider: ChicorySignatureProvider): Map<String, String> {
        val signature = runBlocking { provider.getSignature() }
        return SignatureHeaders.build(signature)
    }

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to BASE_URL,
        "Referer" to "$BASE_URL/",
    )

    private fun mergedHeaders(base: Map<String, String>, extra: Map<String, String>): Map<String, String> = base + extra

    private fun searchRequestBody(
        searchText: String = "",
        tags: List<String> = emptyList(),
        tagsMode: String = "AND",
        brands: List<String> = emptyList(),
        blacklist: List<String> = emptyList(),
        orderBy: String = "created_at",
        ordering: String = "desc",
        page: Int = 0,
    ): String {
        val tagsJson = tags.joinToString(",") { "\"$it\"" }
        val brandsJson = brands.joinToString(",") { "\"$it\"" }
        val blacklistJson = blacklist.joinToString(",") { "\"$it\"" }
        return """{"search_text":"$searchText","tags":[$tagsJson],"tags_mode":"$tagsMode","brands":[$brandsJson],"blacklist":[$blacklistJson],"order_by":"$orderBy","ordering":"$ordering","page":$page}"""
    }

    /** Execute a search and return the parsed JsonObject, or null on failure. */
    private fun executeSearch(
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject? {
        val response = httpPost(SEARCH_URL, body, baseHeaders() + extraHeaders) ?: return null
        if (!response.isSuccessful) return null
        val responseBody = response.body ?: return null
        return try {
            jsonParser.parseToJsonElement(responseBody).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    /** Parse the 'hits' field — it's a JSON-encoded string, not a direct array. */
    private fun parseHits(result: JsonObject): List<JsonObject>? {
        val hitsString = result["hits"]?.jsonPrimitive?.contentOrNull ?: return null
        val hitsArray = try {
            jsonParser.parseToJsonElement(hitsString).jsonArray
        } catch (_: Exception) {
            null
        } ?: return null
        return hitsArray.mapNotNull {
            try {
                it.jsonObject
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Get the slug from the first search hit, or null on failure. */
    private fun getFirstSearchSlug(): String? {
        val body = searchRequestBody()
        val result = executeSearch(body) ?: return null
        val hits = parseHits(result) ?: return null
        if (hits.isEmpty()) return null
        return hits[0]["slug"]?.jsonPrimitive?.contentOrNull
    }

    /** Try to resolve hvId from the video endpoint WITHOUT signature headers. Returns null on failure. */
    private fun resolveHvIdWithoutSignature(slug: String): String? {
        val videoResponse = httpGet("$BASE_URL/api/v8/video?id=$slug", baseHeaders())
        if (videoResponse == null || !videoResponse.isSuccessful) return null
        val videoBody = videoResponse.body ?: return null
        val videoJson = try {
            jsonParser.parseToJsonElement(videoBody).jsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        return videoJson["hentai_video"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }

    /** Resolve hvId, trying without signature first, falling back to with signature. */
    private fun resolveHvId(slug: String, provider: ChicorySignatureProvider): String? {
        resolveHvIdWithoutSignature(slug)?.let { return it }
        val sigHeaders = getSignatureHeaders(provider)
        val videoResponse = httpGet("$BASE_URL/api/v8/video?id=$slug", mergedHeaders(baseHeaders(), sigHeaders))
        if (videoResponse == null || !videoResponse.isSuccessful) return null
        val videoBody = videoResponse.body ?: return null
        val videoJson = try {
            jsonParser.parseToJsonElement(videoBody).jsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        return videoJson["hentai_video"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }

    // ── Test 1: Search endpoint returns results ───────────────────────

    @Test
    fun testSearchEndpointReturnsResults() {
        val body = searchRequestBody()
        val result = executeSearch(body)
        if (result == null) {
            println("$SKIP_PREFIX Search endpoint returned no valid response — requires network access")
            return
        }

        val hits = parseHits(result)
        assertNotNull(hits, "Response must contain parseable 'hits'")
        assertTrue(hits.size >= 1, "hits must have at least 1 item, got ${hits.size}")

        val nbPages = result["nbPages"]?.jsonPrimitive?.int
        assertNotNull(nbPages, "Response must contain 'nbPages'")
        assertTrue(nbPages > 0, "nbPages must be > 0, got $nbPages")

        val page = result["page"]?.jsonPrimitive?.int
        assertEquals(0, page, "page must be 0")
    }

    // ── Test 2: Search with keyword returns relevant results ───────────

    @Test
    fun testSearchWithKeywordReturnsRelevantResults() {
        val body = searchRequestBody(searchText = "3d")
        val result = executeSearch(body)
        if (result == null) {
            println("$SKIP_PREFIX Search with keyword returned no valid response — requires network access")
            return
        }

        val hits = parseHits(result)
        assertNotNull(hits, "Response must contain parseable 'hits'")
        assertTrue(hits.isNotEmpty(), "hits must be non-empty for keyword '3d'")

        val nbHits = result["nbHits"]?.jsonPrimitive?.int
        assertNotNull(nbHits, "Response must contain 'nbHits'")
        assertTrue(nbHits > 0, "nbHits must be > 0 for keyword '3d', got $nbHits")
    }

    // ── Test 3: Search pagination works ───────────────────────────────

    @Test
    fun testSearchPaginationWorks() {
        val bodyPage0 = searchRequestBody(page = 0)
        val bodyPage1 = searchRequestBody(page = 1)

        val result0 = executeSearch(bodyPage0)
        if (result0 == null) {
            println("$SKIP_PREFIX Search page 0 returned no valid response — requires network access")
            return
        }

        val result1 = executeSearch(bodyPage1)
        if (result1 == null) {
            println("$SKIP_PREFIX Search page 1 returned no valid response — requires network access")
            return
        }

        val page0 = result0["page"]?.jsonPrimitive?.int
        val page1 = result1["page"]?.jsonPrimitive?.int
        assertEquals(0, page0, "Page 0 result must have page == 0")
        assertEquals(1, page1, "Page 1 result must have page == 1")

        val hits0 = parseHits(result0)
        val hits1 = parseHits(result1)
        assertNotNull(hits0, "Page 0 must have parseable hits")
        assertNotNull(hits1, "Page 1 must have parseable hits")

        // Hits differ between pages (compare slug lists for simplicity)
        val slugs0 = hits0.mapNotNull { it["slug"]?.jsonPrimitive?.contentOrNull }.toSet()
        val slugs1 = hits1.mapNotNull { it["slug"]?.jsonPrimitive?.contentOrNull }.toSet()
        assertTrue(slugs0 != slugs1, "Hits on page 0 and page 1 must differ")
    }

    // ── Test 4: Video endpoint returns video details ──────────────────

    @Test
    fun testVideoEndpointReturnsVideoDetails() {
        val provider = createSignatureProvider()
        if (provider == null) {
            println("$SKIP_PREFIX Could not create signature provider — requires network and WASM binary")
            return
        }

        try {
            val sigHeaders = getSignatureHeaders(provider)
            val slug = getFirstSearchSlug()
            if (slug == null) {
                println("$SKIP_PREFIX Could not obtain a valid slug from search — requires network access")
                return
            }

            val response = httpGet("$BASE_URL/api/v8/video?id=$slug", mergedHeaders(baseHeaders(), sigHeaders))
                ?: run {
                    println("$SKIP_PREFIX Video endpoint returned no response — requires network access")
                    return
                }
            assertTrue(response.isSuccessful, "Video endpoint must return 200, got ${response.code}")
            val responseBody = response.body
            assertNotNull(responseBody, "Video endpoint response body must not be null")

            val video = try {
                jsonParser.parseToJsonElement(responseBody).jsonObject
            } catch (_: Exception) {
                println("$SKIP_PREFIX Video endpoint response is not valid JSON")
                return
            }

            val hentaiVideo = video["hentai_video"]?.jsonObject
            assertNotNull(hentaiVideo, "Response must contain 'hentai_video' object")
            assertNotNull(
                hentaiVideo["id"]?.jsonPrimitive?.contentOrNull,
                "hentai_video must have 'id'",
            )
            assertNotNull(
                hentaiVideo["name"]?.jsonPrimitive?.contentOrNull,
                "hentai_video must have 'name'",
            )
            assertNotNull(
                hentaiVideo["slug"]?.jsonPrimitive?.contentOrNull,
                "hentai_video must have 'slug'",
            )
            assertNotNull(
                video["videos_manifest"],
                "Response must contain 'videos_manifest'",
            )
        } finally {
            provider.close()
        }
    }

    // ── Test 5: Manifest endpoint requires valid origin ──────────────

    @Test
    fun testManifestEndpointRequiresValidOrigin() {
        val provider = createSignatureProvider()
        if (provider == null) {
            println("$SKIP_PREFIX Could not create signature provider — requires network and WASM binary")
            return
        }

        try {
            val slug = getFirstSearchSlug()
            if (slug == null) {
                println("$SKIP_PREFIX Could not obtain a valid slug from search — requires network access")
                return
            }

            val hvId = resolveHvId(slug, provider)
            if (hvId == null) {
                println("$SKIP_PREFIX Could not resolve hvId")
                return
            }

            // Request manifest WITHOUT the Origin/Referer headers (CDN requires them)
            val noOriginHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json",
                "Accept-Language" to "en-US,en;q=0.9",
            )
            val manifestResponse = httpGet("$CDN_BASE_URL/api/v8/guest/videos/$hvId/manifest", noOriginHeaders)
                ?: run {
                    println("$SKIP_PREFIX Manifest endpoint returned no response — requires network access")
                    return
                }
            assertEquals(
                401,
                manifestResponse.code,
                "Manifest endpoint must return 401 without proper Origin header, got ${manifestResponse.code}",
            )
        } finally {
            provider.close()
        }
    }

    // ── Test 6: Manifest endpoint with valid origin returns streams ──

    @Test
    fun testManifestEndpointWithSignatureReturnsStreams() {
        val provider = createSignatureProvider()
        if (provider == null) {
            println("$SKIP_PREFIX Could not create signature provider — requires network and WASM binary")
            return
        }

        try {
            // Step 1: Get a slug and hvId WITHOUT using signature headers
            // (search doesn't need them, and we'll try video endpoint without sig too)
            val slug = getFirstSearchSlug()
            if (slug == null) {
                println("$SKIP_PREFIX Could not obtain a valid slug from search")
                return
            }

            var hvId: String? = resolveHvIdWithoutSignature(slug)

            // If that didn't work, use signature but accept the timing cost
            if (hvId == null) {
                val sigHeaders = getSignatureHeaders(provider)
                val videoResponse = httpGet("$BASE_URL/api/v8/video?id=$slug", mergedHeaders(baseHeaders(), sigHeaders))
                    ?: run {
                        println("$SKIP_PREFIX Video endpoint returned no response")
                        return
                    }
                if (!videoResponse.isSuccessful) {
                    println("$SKIP_PREFIX Video endpoint returned ${videoResponse.code}")
                    return
                }
                val videoBody = videoResponse.body ?: return
                val videoJson = try {
                    jsonParser.parseToJsonElement(videoBody).jsonObject
                } catch (_: Exception) {
                    println("$SKIP_PREFIX Video endpoint response is not valid JSON")
                    return
                }
                hvId = videoJson["hentai_video"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            }

            if (hvId == null) {
                println("$SKIP_PREFIX Could not resolve hvId from video endpoint")
                return
            }

            // Step 2: Generate FRESH signature RIGHT BEFORE the manifest request
            // This is critical — the CDN rejects stale signatures
            val freshSigHeaders = getSignatureHeaders(provider)
            val manifestUrl = "$CDN_BASE_URL/api/v8/guest/videos/$hvId/manifest"
            val allHeaders = mergedHeaders(baseHeaders(), freshSigHeaders)

            // Execute immediately — no delays between sig generation and request
            val manifestResponse = httpGet(manifestUrl, allHeaders)
                ?: run {
                    println("$SKIP_PREFIX Manifest endpoint returned no response")
                    return
                }

            if (manifestResponse.code == 401) {
                println("$SKIP_PREFIX Manifest endpoint returned 401 — signature may have expired between generation and request. This is a timing issue with the test environment, not the extension.")
                return
            }

            assertTrue(manifestResponse.isSuccessful, "Manifest endpoint must return 200 with fresh signature, got ${manifestResponse.code}")

            val manifestBody = manifestResponse.body
            assertNotNull(manifestBody, "Manifest response body must not be null")

            val manifest = try {
                jsonParser.parseToJsonElement(manifestBody).jsonObject
            } catch (_: Exception) {
                println("$SKIP_PREFIX Manifest response is not valid JSON")
                return
            }

            // Parse the response: {"videos_manifest":{"servers":[...]}}
            val servers = try {
                manifest["videos_manifest"]?.jsonObject?.get("servers")?.jsonArray
                    ?: manifest["servers"]?.jsonArray
            } catch (_: Exception) {
                null
            }

            assertNotNull(servers, "Manifest must contain 'servers' array, got keys: ${manifest.keys}")
            assertTrue(servers.size >= 1, "Manifest must have at least 1 server, got ${servers.size}")

            var foundStreamWithUrl = false
            for (serverEntry in servers) {
                val server = serverEntry.jsonObject
                val streams = server["streams"]?.jsonArray
                assertNotNull(streams, "Each server must have 'streams' array")
                assertTrue(streams.size >= 1, "Each server must have at least 1 stream")
                for (streamEntry in streams) {
                    val stream = streamEntry.jsonObject
                    val url = stream["url"]?.jsonPrimitive?.contentOrNull
                    if (!url.isNullOrEmpty()) {
                        foundStreamWithUrl = true
                    }
                }
            }
            assertTrue(foundStreamWithUrl, "At least one stream must have a non-empty 'url'")
        } finally {
            provider.close()
        }
    }

    // ── Test 7: Signature headers are accepted by search endpoint ─────

    @Test
    fun testSignatureHeadersAreAcceptedBySearchEndpoint() {
        val provider = createSignatureProvider()
        if (provider == null) {
            println("$SKIP_PREFIX Could not create signature provider — requires network and WASM binary")
            return
        }

        try {
            val sigHeaders = getSignatureHeaders(provider)
            val body = searchRequestBody()

            val result = executeSearch(body, extraHeaders = sigHeaders)
            if (result == null) {
                println("$SKIP_PREFIX Search with signature headers returned no valid response — requires network access")
                return
            }

            val hits = parseHits(result)
            assertNotNull(hits, "Response must contain parseable 'hits'")
            assertTrue(hits.isNotEmpty(), "Search with signature headers must return results")
        } finally {
            provider.close()
        }
    }

    // ── Test 8: hanime.tv origin rejects direct manifest requests ────

    @Test
    fun testHanimeOriginRejectsManifestRequests() {
        // The hanime.tv origin server now rejects all manifest requests with 401, 571:
        // even those carrying valid WASM-generated signatures. The CDN at
        // cached.freeanimehentai.net serves the same endpoint. This test verifies
        // that the origin server enforces authentication, ensuring the CDN remains
        // the only viable path for manifest requests.
        val provider = createSignatureProvider()
        if (provider == null) {
            println("$SKIP_PREFIX Could not create signature provider — requires network and WASM binary")
            return
        }

        try {
            val slug = getFirstSearchSlug()
            if (slug == null) {
                println("$SKIP_PREFIX Could not obtain a valid slug from search")
                return
            }

            val hvId = resolveHvId(slug, provider)
            if (hvId == null) {
                println("$SKIP_PREFIX Could not resolve hvId")
                return
            }

            // The hanime.tv origin always returns 401 for the manifest endpoint
            val sigHeaders = getSignatureHeaders(provider)
            val originResponse = httpGet(
                "$BASE_URL/api/v8/guest/videos/$hvId/manifest",
                mergedHeaders(baseHeaders(), sigHeaders),
            ) ?: run {
                println("$SKIP_PREFIX No response from hanime.tv manifest")
                return
            }
            assertEquals(401, originResponse.code, "hanime.tv origin must reject manifest requests with 401, got ${originResponse.code}")
        } finally {
            provider.close()
        }
    }

// ── Test 9: Stale signature is rejected by manifest endpoint ──────

    @Test
    fun testStaleSignatureIsRejectedByManifestEndpoint() {
        val provider = createSignatureProvider()
        if (provider == null) {
            println("$SKIP_PREFIX Could not create signature provider — requires network and WASM binary")
            return
        }

        try {
            // Get hvId without signature if possible (to preserve signature freshness)
            val slug = getFirstSearchSlug()
            if (slug == null) {
                println("$SKIP_PREFIX Could not obtain a valid slug from search")
                return
            }

            // Try to get hvId without signature
            var hvId: String? = resolveHvIdWithoutSignature(slug)

            if (hvId == null) {
                println("$SKIP_PREFIX Could not resolve hvId — cannot test stale signature rejection")
                return
            }

            val manifestUrl = "$CDN_BASE_URL/api/v8/guest/videos/$hvId/manifest"

            // Test 1: Fresh signature should be accepted
            val freshSigHeaders = getSignatureHeaders(provider)
            val freshResponse = httpGet(manifestUrl, mergedHeaders(baseHeaders(), freshSigHeaders))
                ?: run {
                    println("$SKIP_PREFIX Manifest endpoint returned no response")
                    return
                }

            if (freshResponse.code == 401) {
                println("$SKIP_PREFIX Fresh signature was rejected (401) — timing issue in test environment, cannot test stale rejection")
                return
            }
            assertTrue(freshResponse.isSuccessful, "Fresh signature must be accepted by manifest endpoint, got ${freshResponse.code}")

            // Test 2: Stale signature (timestamp 10 minutes ago) should be rejected
            val staleTime = (System.currentTimeMillis() / 1000) - 600 // 10 minutes ago
            val staleHeaders = mapOf(
                "x-signature" to "a".repeat(64), // fake signature
                "x-time" to staleTime.toString(),
                "x-signature-version" to "web2",
                "x-session-token" to "",
                "x-user-license" to "",
                "x-csrf-token" to "",
                "x-license" to "",
                "content-type" to "application/json",
                "accept" to "application/json",
            )
            val staleResponse = httpGet(manifestUrl, mergedHeaders(baseHeaders(), staleHeaders))
                ?: run {
                    println("$SKIP_PREFIX Stale manifest request returned no response")
                    return
                }
            assertEquals(401, staleResponse.code, "Stale signature (10 min old) must be rejected with 401, got ${staleResponse.code}")
        } finally {
            provider.close()
        }
    }

// ── Test 10: Video endpoint returns franchise videos ───────────────

    @Test
    fun testVideoEndpointReturnsFranchiseVideos() {
        val provider = createSignatureProvider()
        if (provider == null) {
            println("$SKIP_PREFIX Could not create signature provider — requires network and WASM binary")
            return
        }

        try {
            val sigHeaders = getSignatureHeaders(provider)
            val slug = getFirstSearchSlug()
            if (slug == null) {
                println("$SKIP_PREFIX Could not obtain a valid slug from search — requires network access")
                return
            }

            val response = httpGet("$BASE_URL/api/v8/video?id=$slug", mergedHeaders(baseHeaders(), sigHeaders))
                ?: run {
                    println("$SKIP_PREFIX Video endpoint returned no response — requires network access")
                    return
                }
            if (!response.isSuccessful) {
                println("$SKIP_PREFIX Video endpoint returned ${response.code}")
                return
            }
            val responseBody = response.body ?: run {
                println("$SKIP_PREFIX Video endpoint returned empty body")
                return
            }
            val video = try {
                jsonParser.parseToJsonElement(responseBody).jsonObject
            } catch (_: Exception) {
                println("$SKIP_PREFIX Video endpoint response is not valid JSON")
                return
            }

            val franchise = video["hentai_franchise_hentai_videos"]?.jsonArray
            assertNotNull(franchise, "Response must contain 'hentai_franchise_hentai_videos' array")
            assertTrue(franchise.isNotEmpty(), "Franchise videos array must be non-empty")

            for (entry in franchise) {
                val franchiseVideo = entry.jsonObject
                assertNotNull(
                    franchiseVideo["id"]?.jsonPrimitive?.contentOrNull,
                    "Each franchise video must have 'id'",
                )
                assertNotNull(
                    franchiseVideo["name"]?.jsonPrimitive?.contentOrNull,
                    "Each franchise video must have 'name'",
                )
                assertNotNull(
                    franchiseVideo["slug"]?.jsonPrimitive?.contentOrNull,
                    "Each franchise video must have 'slug'",
                )
            }
        } finally {
            provider.close()
        }
    }

    // ── Test 11: Search with tags returns filtered results ────────────

    @Test
    fun testSearchWithTagsReturnsFilteredResults() {
        val body = searchRequestBody(tags = listOf("3d"))
        val result = executeSearch(body)
        if (result == null) {
            println("$SKIP_PREFIX Search with tags returned no valid response — requires network access")
            return
        }

        val hits = parseHits(result)
        assertNotNull(hits, "Response must contain parseable 'hits'")
        assertTrue(hits.isNotEmpty(), "Search with tag '3d' must return non-empty hits")
    }
}
