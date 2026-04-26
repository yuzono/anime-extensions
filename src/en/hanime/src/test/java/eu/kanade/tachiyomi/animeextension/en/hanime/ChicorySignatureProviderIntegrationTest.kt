package eu.kanade.tachiyomi.animeextension.en.hanime

import org.junit.Before
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChicorySignatureProviderIntegrationTest {

    // ── JVM test setup (Base64) ──────────────────────────────────────

    @Before
    fun setUp() {
        Base64Provider.instance = JvmBase64
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val SKIP_PREFIX = "SKIP:"
    }

    /**
     * Fetch the WASM binary from the live hanime.tv site.
     *
     * Uses [HanimeWasmBinary.extractVendorJsUrl] and [HanimeWasmBinary.extractWasmFromVendorJs]
     * to replicate the production extraction flow in a test context.
     * Returns null if the network is unavailable (test is skipped).
     */
    private fun getWasmBinary(): ByteArray? {
        return try {
            // Step 1: Fetch homepage
            val homeConn = URL("https://hanime.tv").openConnection() as HttpURLConnection
            homeConn.requestMethod = "GET"
            homeConn.connectTimeout = 15000
            homeConn.readTimeout = 30000
            homeConn.setRequestProperty("User-Agent", USER_AGENT)
            homeConn.setRequestProperty("Accept", "text/html,application/javascript,*/*")
            if (homeConn.responseCode !in 200..299) return null

            val homeHtml = try {
                homeConn.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                null
            }
            homeConn.disconnect()

            if (homeHtml == null) return null

            // Step 2: Extract vendor.js URL
            val vendorJsUrl = HanimeWasmBinary.extractVendorJsUrl(homeHtml) ?: return null

            // Step 3: Fetch vendor.js
            val vendorConn = URL(vendorJsUrl).openConnection() as HttpURLConnection
            vendorConn.requestMethod = "GET"
            vendorConn.connectTimeout = 15000
            vendorConn.readTimeout = 30000
            vendorConn.setRequestProperty("User-Agent", USER_AGENT)
            vendorConn.setRequestProperty("Accept", "text/html,application/javascript,*/*")
            if (vendorConn.responseCode !in 200..299) return null

            val vendorJs = try {
                vendorConn.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                null
            }
            vendorConn.disconnect()

            if (vendorJs == null) return null

            // Step 4: Extract WASM binary
            HanimeWasmBinary.extractWasmFromVendorJs(vendorJs)
        } catch (_: Exception) {
            null
        }
    }

    @Test
    fun testEndToEndSignatureGeneration() {
        val binary = getWasmBinary()
        if (binary == null) {
            println("$SKIP_PREFIX Could not fetch WASM binary from hanime.tv — integration test requires network access")
            return
        }

        val provider = ChicorySignatureProvider(binary)

        val signature = kotlinx.coroutines.runBlocking {
            provider.getSignature()
        }

        // Verify signature format: 64 lowercase hex characters
        assertNotNull(signature.signature, "Signature must not be null")
        assertEquals(
            64,
            signature.signature.length,
            "Signature must be 64 hex chars, got: '${signature.signature}' (len=${signature.signature.length})",
        )
        assertTrue(
            signature.signature.all { it in '0'..'9' || it in 'a'..'f' },
            "Signature must be lowercase hex, got: ${signature.signature}",
        )

        // Verify timestamp is a valid Unix timestamp within ±30 seconds of now
        val time = signature.time.toLongOrNull()
        assertNotNull(time, "Timestamp must be a valid number, got: ${signature.time}")
        val now = System.currentTimeMillis() / 1000
        assertTrue(
            time in (now - 30)..(now + 30),
            "Timestamp must be within 30 seconds of now. Got: $time, Now: $now",
        )

        provider.close()
    }

    @Test
    fun testMultipleSignaturesAreDifferent() {
        val binary = getWasmBinary()
        if (binary == null) {
            println("$SKIP_PREFIX Could not fetch WASM binary from hanime.tv")
            return
        }

        val provider = ChicorySignatureProvider(binary)

        val sig1 = kotlinx.coroutines.runBlocking {
            provider.getSignature()
        }

        // Wait for the timestamp to change (1 second + margin)
        Thread.sleep(1100)

        val sig2 = kotlinx.coroutines.runBlocking {
            provider.getSignature()
        }

        // Both signatures must be valid 64-char hex strings
        assertEquals(64, sig1.signature.length, "First signature must be 64 hex chars")
        assertEquals(64, sig2.signature.length, "Second signature must be 64 hex chars")

        assertNotEquals("Two signatures generated seconds apart should differ", sig1.signature, sig2.signature)

        provider.close()
    }

    @Test
    fun testSignatureHeadersAreCorrect() {
        val binary = getWasmBinary()
        if (binary == null) {
            println("$SKIP_PREFIX Could not fetch WASM binary from hanime.tv")
            return
        }

        val provider = ChicorySignatureProvider(binary)

        val signature = kotlinx.coroutines.runBlocking {
            provider.getSignature()
        }

        val headers = SignatureHeaders.build(signature)

        assertEquals(signature.signature, headers["x-signature"], "x-signature header must match signature")
        assertEquals(signature.time, headers["x-time"], "x-time header must match timestamp")
        assertEquals("web2", headers["x-signature-version"], "x-signature-version must be 'web2'")
        assertEquals("", headers["x-session-token"], "x-session-token must be empty")
        assertEquals("", headers["x-user-license"], "x-user-license must be empty")
        assertEquals("", headers["x-csrf-token"], "x-csrf-token must be empty")
        assertEquals("", headers["x-license"], "x-license must be empty")

        provider.close()
    }

    @Test
    fun testProviderNameIsChicoryInterpreter() {
        val binary = getWasmBinary()
        if (binary == null) {
            println("$SKIP_PREFIX Could not fetch WASM binary from hanime.tv")
            return
        }

        val provider = ChicorySignatureProvider(binary)
        assertEquals("ChicoryInterpreter", provider.name, "Provider name must be 'ChicoryInterpreter'")
    }

    @Test
    fun testCloseAfterInitialization() {
        val binary = getWasmBinary()
        if (binary == null) {
            println("$SKIP_PREFIX Could not fetch WASM binary from hanime.tv")
            return
        }

        val provider = ChicorySignatureProvider(binary)
        kotlinx.coroutines.runBlocking { provider.getSignature() }
        provider.close()

        // Double-close should be safe
        provider.close()
    }
}
