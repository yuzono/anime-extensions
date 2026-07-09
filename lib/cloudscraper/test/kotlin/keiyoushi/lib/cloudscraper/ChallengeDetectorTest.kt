package keiyoushi.lib.cloudscraper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChallengeDetectorTest {

    // ── JSD challenge detection ───────────────────────────────────────

    @Test
    fun `detect returns JSD when __CF$cv$params has a and s keys`() {
        val html = """
            <html>
            <head><title>Just a moment...</title></head>
            <body>
            <script>
                window.__CF${'$'}cv${'$'}params = {"a":"abc123def","s":"/cdn-cgi/challenge-platform/scripts/jsd/main.js","h":"def45678"};
            </script>
            </body>
            </html>
        """.trimIndent()

        val info = ChallengeDetector.detect(html)

        assertEquals(ChallengeType.JSD, info.type)
        assertNotNull(info.rawCvParams)
        assertTrue(info.rawCvParams!!.contains("abc123def"))
    }

    @Test
    fun `detect returns JSD when __CF$cv$params uses single quotes`() {
        val html = """
            <html>
            <body>
            <script>
                window.__CF${'$'}cv${'$'}params = {'a':'abc123def','s':'/cdn-cgi/jsd/main.js','h':'def45678'};
            </script>
            </body>
            </html>
        """.trimIndent()

        val info = ChallengeDetector.detect(html)

        assertEquals(ChallengeType.JSD, info.type)
        assertNotNull(info.rawCvParams)
    }

    @Test
    fun `detect returns JSD for r-t variant and builds synthetic params`() {
        val html = """
            <html>
            <head><title>Just a moment...</title></head>
            <body>
            <script>
                window.__CF${'$'}cv${'$'}params = {"r":"abc123def","t":"MTIzNDU2Nzg5MA=="};
            </script>
            <script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script>
            </body>
            </html>
        """.trimIndent()

        val info = ChallengeDetector.detect(html)

        assertEquals(ChallengeType.JSD, info.type)
        assertNotNull(info.rawCvParams)
        // Should have built synthetic a/s/h from r and main.js URL
        assertTrue(info.rawCvParams!!.contains("abc123def"), "synthetic params should contain r value as a")
    }

    @Test
    fun `detect returns UNSOLVABLE for managed challenge page`() {
        val html = """
            <html>
            <head><title>Just a moment...</title></head>
            <body>
            <div id="challenge-running">Checking your browser...</div>
            <script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script>
            </body>
            </html>
        """.trimIndent()

        val info = ChallengeDetector.detect(html)

        assertEquals(ChallengeType.UNSOLVABLE, info.type)
        assertNull(info.rawCvParams)
    }

    @Test
    fun `detect returns UNSOLVABLE for turnstile challenge`() {
        val html = """
            <html>
            <head><title>Just a moment...</title></head>
            <body>
            <div id="cf-turnstile" data-sitekey="0x4AAAAAAABC"></div>
            </body>
            </html>
        """.trimIndent()

        val info = ChallengeDetector.detect(html)

        assertEquals(ChallengeType.UNSOLVABLE, info.type)
        assertNull(info.rawCvParams)
    }

    @Test
    fun `detect returns UNSOLVABLE for non-CF page`() {
        val html = """
            <html>
            <head><title>Miruro</title></head>
            <body>
            <div id="app">Welcome!</div>
            </body>
            </html>
        """.trimIndent()

        val info = ChallengeDetector.detect(html)

        assertEquals(ChallengeType.UNSOLVABLE, info.type)
        assertNull(info.rawCvParams)
    }

    @Test
    fun `detect returns UNSOLVABLE for CF block page despite iframe-injected JSD`() {
        val html = """
            <!DOCTYPE html>
            <html><head><title>Attention Required! | Cloudflare</title></head>
            <body>
            <div id="cf-error-details" class="cf-error-details-wrapper">
                <h1>Sorry, you have been blocked</h1>
                <p>You are unable to access example.com</p>
            </div>
            <script>
                (function(){var d=document.createElement('script');
                d.innerHTML="window.__CF${'$'}cv${'$'}params={r:'abc123def',t:'MTIzNA=='};";
                })();
            </script>
            </body></html>
        """.trimIndent()

        val info = ChallengeDetector.detect(html)

        // Block pages contain __CF\$cv\$params injected into a hidden iframe,
        // which would match the JSD regex if we didn't check for block markers first.
        assertEquals(ChallengeType.UNSOLVABLE, info.type)
        assertNull(info.rawCvParams)
    }

    @Test
    fun `detect returns UNSOLVABLE for CF block page from real fixture`() {
        val html = java.io.File(
            "test/fixtures/cf_challenge_403.html",
        ).readText()

        val info = ChallengeDetector.detect(html)

        // The real block page has cf-error-details wrapper and "blocked" heading.
        assertEquals(ChallengeType.UNSOLVABLE, info.type)
        assertNull(info.rawCvParams)
    }

    @Test
    fun `detect returns UNSOLVABLE for empty page`() {
        val info = ChallengeDetector.detect("")

        assertEquals(ChallengeType.UNSOLVABLE, info.type)
        assertNull(info.rawCvParams)
    }

    // ── Realistic JSD challenge page ──────────────────────────────────

    @Test
    fun `detect handles realistic JSD page structure`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Just a moment...</title>
                <link rel="icon" href="data:image/x-icon;base64,AA">
            </head>
            <body>
                <div id="cf-please-wait">Please wait while you are redirected...</div>
                <script>
                    window.__CF${'$'}cv${'$'}params = {"a":"9f285a71c8b3cb7","s":"/cdn-cgi/challenge-platform/scripts/jsd/main.js","h":"8b3cb7"};
                </script>
                <script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script>
                <noscript>
                    Enable JavaScript and cookies to continue.
                </noscript>
            </body>
            </html>
        """.trimIndent()

        val info = ChallengeDetector.detect(html)

        assertEquals(ChallengeType.JSD, info.type)
        assertNotNull(info.rawCvParams)
        assertTrue(info.rawCvParams!!.contains("9f285a71c8b3cb7"))
    }
}
