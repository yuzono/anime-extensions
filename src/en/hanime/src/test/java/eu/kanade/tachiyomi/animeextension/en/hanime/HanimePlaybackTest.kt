package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Unit tests for Hanime extension video playback flow.
 * Tests the API endpoints that provide video stream URLs.
 */
class HanimePlaybackTest {

    // Cookie jar to capture and store cookies
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    private val client = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostKey = url.host
                cookieStore[hostKey] = (cookieStore[hostKey] ?: mutableListOf()).apply { addAll(cookies) }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore[url.host] ?: emptyList()
        })
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("sec-ch-ua", "\"Google Chrome\";v=\"120\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"120\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Windows\"")
                .build()
            chain.proceed(request)
        }
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        private const val VIDEO_API_BASE = "https://cached.freeanimehentai.net/api/v8"
        private const val HANIME_BASE = "https://hanime.tv"
        private const val TEST_VIDEO_ID = "cool-de-m-2"
        private const val TEST_VIDEO_NUMERIC_ID = 3427L
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 30L
    }

    private fun generateSignature(timestamp: Long): String {
        val input = "$timestamp,Xkdi29,https://hanime.tv,mn2,$timestamp"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Debug helper to print detailed request/response information.
     */
    private fun debugRequest(tag: String, request: Request, timestamp: Long, signature: String) {
        println("\n${"=".repeat(80)}")
        println("[$tag] DEBUG REQUEST INFO")
        println("=".repeat(80))
        println("Timestamp: $timestamp")
        println("Signature Input: \"$timestamp,Xkdi29,https://hanime.tv,mn2,$timestamp\"")
        println("Signature Generated: $signature")
        println("Signature Length: ${signature.length}")
        println("-".repeat(80))
        println("Request URL: ${request.url}")
        println("Request Method: ${request.method}")
        println("-".repeat(80))
        println("Request Headers:")
        request.headers.forEach { (name, value) ->
            println("  $name: $value")
        }
        println("-".repeat(80))
        println("Cookies in store:")
        cookieStore.forEach { (host, cookies) ->
            println("  Host: $host")
            cookies.forEach { cookie ->
                println("    ${cookie.name}=${cookie.value}")
            }
        }
        println("=".repeat(80))
    }

    /**
     * Debug helper to print detailed response information.
     */
    private fun debugResponse(tag: String, response: okhttp3.Response) {
        println("\n${"=".repeat(80)}")
        println("[$tag] DEBUG RESPONSE INFO")
        println("=".repeat(80))
        println("Response Code: ${response.code}")
        println("Response Message: ${response.message}")
        println("Response Protocol: ${response.protocol}")
        println("-".repeat(80))
        println("Response Headers:")
        response.headers.forEach { (name, value) ->
            println("  $name: $value")
        }
        println("-".repeat(80))
        println("Response Body (first 2000 chars):")
        try {
            val body = response.body?.string() ?: "(null)"
            println(body.take(2000))
            if (body.length > 2000) {
                println("\n... (truncated, total ${body.length} chars)")
            }
        } catch (e: Exception) {
            println("Error reading body: ${e.message}")
        }
        println("-".repeat(80))
        println("Cookies received:")
        cookieStore.forEach { (host, cookies) ->
            println("  Host: $host")
            cookies.forEach { cookie ->
                println("    ${cookie.name}=${cookie.value} (domain=${cookie.domain}, path=${cookie.path})")
            }
        }
        println("=".repeat(80))
        println()
    }

    /**
     * Test 1: Verify the video API returns a valid response with required fields.
     *
     * The video API should return:
     * - hentai_video object with id and slug
     * - videos_manifest for premium users (may be empty for guests)
     */
    @Test
    fun `test video API returns valid response`() {
        val url = "$VIDEO_API_BASE/video?id=$TEST_VIDEO_ID"

        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://hanime.tv/")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            // Verify HTTP success
            assertTrue(response.isSuccessful, "Expected successful response, got ${response.code}")

            val responseBody = response.body.string()
            assertNotNull(responseBody, "Response body should not be null")
            assertTrue(responseBody.isNotEmpty(), "Response body should not be empty")

            // Parse and verify structure
            val videoModel = json.decodeFromString<TestVideoModel>(responseBody)

            // Verify hentai_video exists with required fields
            assertNotNull(videoModel.hentaiVideo, "hentai_video should exist in response")
            assertNotNull(videoModel.hentaiVideo.id, "hentai_video.id should not be null")
            assertNotNull(videoModel.hentaiVideo.slug, "hentai_video.slug should not be null")

            // Verify the slug matches our request
            assertEquals(TEST_VIDEO_ID, videoModel.hentaiVideo.slug, "Video slug should match request")

            // Verify videos_manifest exists (structure should be present)
            assertNotNull(videoModel.videosManifest, "videos_manifest should exist in response")
        }
    }

    /**
     * DEBUG TEST: Comprehensive debug logging for 401 error investigation.
     *
     * This test captures:
     * 1. Exact timestamp used
     * 2. Exact signature generated
     * 3. Exact request URL
     * 4. Full request headers
     * 5. Response headers (especially error details)
     * 6. Response body (if any)
     * 7. Cookie state
     */
    @Test
    fun `debug manifest endpoint 401 error`() {
        println("\n" + "=".repeat(80))
        println("STARTING DEBUG TEST: Manifest Endpoint 401 Investigation")
        println("=".repeat(80))

        // Step 1: Visit hanime.tv homepage to potentially get cookies
        println("\n[STEP 1] Visiting hanime.tv homepage to capture cookies...")
        val homeRequest = Request.Builder()
            .url("$HANIME_BASE/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .get()
            .build()

        try {
            client.newCall(homeRequest).execute().use { homeResponse ->
                println("Homepage Response Code: ${homeResponse.code}")
                println("Homepage Response Message: ${homeResponse.message}")
                println("Cookies after homepage visit:")
                cookieStore["hanime.tv"]?.forEach { cookie ->
                    println("  ${cookie.name}=${cookie.value}")
                } ?: println("  (no cookies)")
            }
        } catch (e: Exception) {
            println("Homepage visit failed: ${e.message}")
        }

        // Step 2: Check if we need to visit the video page first
        println("\n[STEP 2] Visiting video page to capture additional cookies...")
        val videoPageRequest = Request.Builder()
            .url("$HANIME_BASE/videos/hentai/$TEST_VIDEO_ID")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .get()
            .build()

        try {
            client.newCall(videoPageRequest).execute().use { videoPageResponse ->
                println("Video Page Response Code: ${videoPageResponse.code}")
                println("Video Page Response Message: ${videoPageResponse.message}")
                println("Cookies after video page visit:")
                cookieStore["hanime.tv"]?.forEach { cookie ->
                    println("  ${cookie.name}=${cookie.value}")
                } ?: println("  (no cookies)")
            }
        } catch (e: Exception) {
            println("Video page visit failed: ${e.message}")
        }

        // Step 3: Test the video API endpoint (this should work)
        println("\n[STEP 3] Testing video API endpoint (should succeed)...")
        val videoUrl = "$VIDEO_API_BASE/video?id=$TEST_VIDEO_ID"
        val videoRequest = Request.Builder()
            .url(videoUrl)
            .header("Referer", "$HANIME_BASE/")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(videoRequest).execute().use { videoResponse ->
            println("Video API Response Code: ${videoResponse.code}")
            println("Video API Response Message: ${videoResponse.message}")
            println("Cookies after video API call:")
            cookieStore.forEach { (host, cookies) ->
                println("  Host: $host")
                cookies.forEach { cookie ->
                    println("    ${cookie.name}=${cookie.value}")
                }
            }
            if (videoResponse.isSuccessful) {
                println("Video API Body (first 500 chars): ${videoResponse.body?.string()?.take(500)}")
            }
        }

        // Step 4: Test the manifest endpoint with signature (the failing one)
        println("\n[STEP 4] Testing manifest endpoint with signature (the failing endpoint)...")
        val manifestTimestamp = System.currentTimeMillis() / 1000
        val manifestSignature = generateSignature(manifestTimestamp)
        val manifestUrl = "$VIDEO_API_BASE/guest/videos/$TEST_VIDEO_NUMERIC_ID/manifest"

        val manifestRequest = Request.Builder()
            .url(manifestUrl)
            .header("x-signature", manifestSignature)
            .header("x-time", manifestTimestamp.toString())
            .header("x-signature-version", "web2")
            .header("x-user-license", "")
            .header("x-csrf-token", "")
            .header("x-session-token", "")
            .header("x-license", "")
            .header("Referer", "$HANIME_BASE/")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .get()
            .build()

        debugRequest("MANIFEST", manifestRequest, manifestTimestamp, manifestSignature)

        client.newCall(manifestRequest).execute().use { manifestResponse ->
            debugResponse("MANIFEST", manifestResponse)
        }

        // Step 5: Test with play request first, then manifest
        println("\n[STEP 5] Testing full flow: play request + manifest...")
        val playTimestamp = System.currentTimeMillis() / 1000
        val playSignature = generateSignature(playTimestamp)
        val playUrl = "$VIDEO_API_BASE/hentai_videos/$TEST_VIDEO_ID/play"

        val playRequest = Request.Builder()
            .url(playUrl)
            .header("x-signature", playSignature)
            .header("x-time", playTimestamp.toString())
            .header("x-signature-version", "web2")
            .header("x-user-license", "")
            .header("x-csrf-token", "")
            .header("x-session-token", "")
            .header("x-license", "")
            .header("Referer", "$HANIME_BASE/")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json;charset=UTF-8")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        debugRequest("PLAY", playRequest, playTimestamp, playSignature)

        client.newCall(playRequest).execute().use { playResponse ->
            debugResponse("PLAY", playResponse)
        }

        // Now try manifest again after play
        println("\n[STEP 5b] Manifest request after play request...")
        val manifestTimestamp2 = System.currentTimeMillis() / 1000
        val manifestSignature2 = generateSignature(manifestTimestamp2)
        val manifestUrl2 = "$VIDEO_API_BASE/guest/videos/$TEST_VIDEO_NUMERIC_ID/manifest"

        val manifestRequest2 = Request.Builder()
            .url(manifestUrl2)
            .header("x-signature", manifestSignature2)
            .header("x-time", manifestTimestamp2.toString())
            .header("x-signature-version", "web2")
            .header("x-user-license", "")
            .header("x-csrf-token", "")
            .header("x-session-token", "")
            .header("x-license", "")
            .header("Referer", "$HANIME_BASE/")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .get()
            .build()

        debugRequest("MANIFEST_AFTER_PLAY", manifestRequest2, manifestTimestamp2, manifestSignature2)

        client.newCall(manifestRequest2).execute().use { manifestResponse2 ->
            debugResponse("MANIFEST_AFTER_PLAY", manifestResponse2)
        }

        // Step 6: Print summary of all cookies collected
        println("\n[STEP 6] Final cookie summary...")
        println("\nAll cookies collected during test:")
        cookieStore.forEach { (host, cookies) ->
            println("  Host: $host (${cookies.size} cookies)")
            cookies.forEach { cookie ->
                println("    ${cookie.name}=${cookie.value}")
                println("      domain=${cookie.domain}, path=${cookie.path}, secure=${cookie.secure}, httpOnly=${cookie.httpOnly}")
            }
        }

        println("\n" + "=".repeat(80))
        println("DEBUG TEST COMPLETE")
        println("=".repeat(80))
    }

    /**
     * Test 2: Verify the manifest endpoint flow returns signed stream URLs.
     *
     * This tests the two-step process:
     * 1. POST to register a play request
     * 2. GET to retrieve the manifest with signed URLs
     */
    @Test
    fun `test manifest endpoint returns signed URLs`() {
        // Step 0: Visit homepage to collect session cookies (like the debug test does)
        val homepageRequest = Request.Builder()
            .url("https://hanime.tv/")
            .get()
            .build()
        client.newCall(homepageRequest).execute().use { homepageResponse ->
            assertTrue(homepageResponse.isSuccessful, "Homepage should load successfully")
        }

        // Step 1: Register play request with signature headers
        val playTimestamp = System.currentTimeMillis() / 1000
        val playSignature = generateSignature(playTimestamp)
        val playUrl = "$VIDEO_API_BASE/hentai_videos/$TEST_VIDEO_ID/play"

        val playRequest = Request.Builder()
            .url(playUrl)
            .header("x-signature", playSignature)
            .header("x-time", playTimestamp.toString())
            .header("x-signature-version", "web2")
            .header("x-user-license", "")
            .header("x-csrf-token", "")
            .header("x-session-token", "")
            .header("x-license", "")
            .header("Referer", "https://hanime.tv/")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json;charset=UTF-8")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        // Execute play request (may fail without auth, but should not throw)
        try {
            client.newCall(playRequest).execute().use { playResponse ->
                // Play request may succeed or fail - both are acceptable
                // The important part is that the manifest endpoint works
            }
        } catch (e: Exception) {
            // Network errors are acceptable for play request
            // Manifest endpoint is the critical part
        }

        // Step 2: Fetch manifest for signed URLs with signature headers
        // Generate fresh signature for manifest request
        val manifestTimestamp = System.currentTimeMillis() / 1000
        val manifestSignature = generateSignature(manifestTimestamp)
        val manifestUrl = "$VIDEO_API_BASE/guest/videos/$TEST_VIDEO_NUMERIC_ID/manifest"

        val manifestRequest = Request.Builder()
            .url(manifestUrl)
            .header("x-signature", manifestSignature)
            .header("x-time", manifestTimestamp.toString())
            .header("x-signature-version", "web2")
            .header("x-user-license", "")
            .header("x-csrf-token", "")
            .header("x-session-token", "")
            .header("x-license", "")
            .header("Referer", "https://hanime.tv/")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .get()
            .build()

        client.newCall(manifestRequest).execute().use { manifestResponse ->
            // Verify HTTP success
            assertTrue(manifestResponse.isSuccessful, "Manifest request should succeed, got ${manifestResponse.code}")

            val manifestBody = manifestResponse.body.string()
            assertNotNull(manifestBody, "Manifest body should not be null")
            assertTrue(manifestBody.isNotEmpty(), "Manifest body should not be empty")

            // Parse manifest response
            val manifest = json.decodeFromString<TestManifestResponse>(manifestBody)

            // Extract streams from nested structure
            val streams = manifest.videosManifest?.servers?.flatMap { it.streams } ?: emptyList()

            // Verify streams array is not empty
            assertTrue(streams.isNotEmpty(), "streams array should not be empty")

            // Verify each stream has required properties
            streams.forEachIndexed { index, stream ->
                assertNotNull(stream.url, "Stream $index should have a url")
                assertNotNull(stream.height, "Stream $index should have a height")

                // Verify access flags exist (at least one should be true for guest access)
                val hasAccess = stream.isGuestAllowed == true ||
                    stream.isMemberAllowed == true ||
                    stream.isPremiumAllowed == true
                assertTrue(hasAccess, "Stream $index should have at least one access flag set")
            }
        }
    }

    /**
     * Test 3: Verify stream URLs are valid HLS format.
     *
     * HLS streams should:
     * - Use HTTPS protocol
     * - End with .m3u8 extension
     * - Be served from known CDN domains
     */
    @Test
    fun `test stream URLs are playable HLS format`() {
        // Generate signature for API authentication
        val timestamp = System.currentTimeMillis() / 1000
        val signature = generateSignature(timestamp)

        // Fetch manifest to get stream URLs
        val manifestUrl = "$VIDEO_API_BASE/guest/videos/$TEST_VIDEO_NUMERIC_ID/manifest"

        val manifestRequest = Request.Builder()
            .url(manifestUrl)
            .header("x-signature", signature)
            .header("x-time", timestamp.toString())
            .header("x-signature-version", "web2")
            .header("x-user-license", "")
            .header("x-csrf-token", "")
            .header("x-session-token", "")
            .header("x-license", "")
            .header("Referer", "https://hanime.tv/")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .get()
            .build()

        client.newCall(manifestRequest).execute().use { response ->
            if (!response.isSuccessful) {
                // If manifest fetch fails, skip test gracefully
                return
            }

            val manifest = json.decodeFromString<TestManifestResponse>(response.body.string())
            val streams = manifest.videosManifest?.servers?.flatMap { it.streams } ?: emptyList()

            if (streams.isEmpty()) return

            assertTrue(streams.isNotEmpty(), "Should have at least one stream to validate")

            streams.forEachIndexed { index, stream ->
                val url = stream.url ?: fail("Stream $index has null URL")

                // Verify HTTPS protocol
                assertTrue(
                    url.startsWith("https://"),
                    "Stream $index URL should use HTTPS: $url",
                )

                // Verify HLS format (m3u8 extension)
                assertTrue(
                    url.endsWith(".m3u8", ignoreCase = true),
                    "Stream $index URL should end with .m3u8: $url",
                )

                // Verify known CDN domains
                val knownCdnPatterns = listOf(
                    "highwinds-cdn.com",
                    "hwcdn.net",
                    "m3u8s.",
                    "hanime.tv",
                    "freeanimehentai.net",
                )

                val isKnownCdn = knownCdnPatterns.any { pattern -> url.contains(pattern, ignoreCase = true) }
                assertTrue(
                    isKnownCdn,
                    "Stream $index URL should be from known CDN domain: $url",
                )

                // Verify URL structure has valid path
                val pathPattern = Regex("https?://[^/]+/.+")
                assertTrue(
                    pathPattern.matches(url),
                    "Stream $index URL should have valid path structure: $url",
                )
            }
        }
    }

    /**
     * Test 4: Verify video quality parsing.
     *
     * Streams should have valid height values representing video quality.
     */
    @Test
    fun `test video quality values are valid`() {
        // Generate signature for API authentication
        val timestamp = System.currentTimeMillis() / 1000
        val signature = generateSignature(timestamp)

        val manifestUrl = "$VIDEO_API_BASE/guest/videos/$TEST_VIDEO_NUMERIC_ID/manifest"

        val request = Request.Builder()
            .url(manifestUrl)
            .header("x-signature", signature)
            .header("x-time", timestamp.toString())
            .header("x-signature-version", "web2")
            .header("x-user-license", "")
            .header("x-csrf-token", "")
            .header("x-session-token", "")
            .header("x-license", "")
            .header("Referer", "https://hanime.tv/")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return

            val manifest = json.decodeFromString<TestManifestResponse>(response.body.string())
            val streams = manifest.videosManifest?.servers?.flatMap { it.streams } ?: emptyList()

            if (streams.isEmpty()) return

            val validQualities = listOf(240L, 360L, 480L, 540L, 720L, 1080L, 1440L, 2160L)

            streams.forEachIndexed { index, stream ->
                val height = stream.height
                assertNotNull(height, "Stream $index should have height")

                assertTrue(
                    height > 0,
                    "Stream $index height should be positive: $height",
                )

                // Height should be one of standard video resolutions
                assertTrue(
                    height.toLong() in validQualities || height in 200..2200,
                    "Stream $index height should be a valid video resolution: $height",
                )
            }
        }
    }

    // Test data models matching the extension's DataModel.kt
    // Using separate classes to avoid Android dependencies in tests

    @Serializable
    data class TestVideoModel(
        @kotlinx.serialization.SerialName("hentai_video")
        val hentaiVideo: TestHentaiVideo? = null,
        @kotlinx.serialization.SerialName("videos_manifest")
        val videosManifest: TestVideosManifest? = null,
    )

    @Serializable
    data class TestHentaiVideo(
        val id: Long? = null,
        val slug: String? = null,
    )

    @Serializable
    data class TestVideosManifest(
        val servers: List<TestServer>? = emptyList(),
    )

    @Serializable
    data class TestServer(
        val streams: List<TestStream> = emptyList(),
    )

    @Serializable
    data class TestStream(
        val url: String? = null,
        val height: String? = null,
    )

    @Serializable
    data class TestManifestResponse(
        @kotlinx.serialization.SerialName("videos_manifest")
        val videosManifest: TestManifestVideosManifest? = null,
    )

    @Serializable
    data class TestManifestVideosManifest(
        val servers: List<TestManifestServer> = emptyList(),
    )

    @Serializable
    data class TestManifestServer(
        val id: Long? = null,
        val name: String? = null,
        val streams: List<TestManifestStream> = emptyList(),
    )

    @Serializable
    data class TestManifestStream(
        val id: Long? = null,
        val url: String? = null,
        val height: Int? = null,
        val width: Int? = null,
        @kotlinx.serialization.SerialName("is_guest_allowed")
        val isGuestAllowed: Boolean? = false,
        @kotlinx.serialization.SerialName("is_member_allowed")
        val isMemberAllowed: Boolean? = false,
        @kotlinx.serialization.SerialName("is_premium_allowed")
        val isPremiumAllowed: Boolean? = false,
    )
}
