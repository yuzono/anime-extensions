package keiyoushi.lib.cloudscraper

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Tests using the real Cloudflare JSD script captured via Playwright.
 *
 * These tests validate that [JsdSolver.extractDeobfuscationPieces] works
 * against the actual JSD sensor script served by Cloudflare for miruro.tv,
 * which uses function names `K` (D-array) and `E` (R-function, offset 453).
 */
class JsdSolverRealScriptTest {

    private val solver = JsdSolver(
        client = okhttp3.OkHttpClient(),
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    )

    @Test
    fun `extractDeobfuscationPieces handles real CF JSD script`() {
        val script = java.io.File(
            "test/fixtures/cf_jsd_main.js",
        ).readText()

        val pieces = solver.extractDeobfuscationPieces(script)

        assertNotNull(pieces) {
            "Must extract deobfuscation pieces from real CF JSD script. " +
                "The script uses D-function named 'K' and R-function named 'E' (offset=453)."
        }
        assertEquals("K", pieces.dName, "Real CF script uses D-function named 'K'")
        assertEquals(453, pieces.offset, "Real CF script uses R-function with offset 453")
        assertTrue(pieces.dString.contains("jsd"), "D-array should contain JSD-related props")
        assertTrue(pieces.dString.contains("__CF\$cv\$params"), "D-array should reference __CF\$cv\$params")
        assertTrue(pieces.dString.contains("navigator"), "D-array should reference navigator")
        assertTrue(pieces.dString.contains("document"), "D-array should reference document")
        assertTrue(pieces.dString.contains("XMLHttpRequest"), "D-array should reference XHR")
    }
}
