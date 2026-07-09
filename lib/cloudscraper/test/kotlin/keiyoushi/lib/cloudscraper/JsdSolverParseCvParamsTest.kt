package keiyoushi.lib.cloudscraper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [JsdSolver.parseCvParams].
 *
 * These tests validate parsing of `window.__CF$cv$params` JSON/JS objects
 * from the challenge HTML. The rawCvParams string is the inner `{...}`
 * extracted by [ChallengeDetector] from `window.__CF$cv$params = {...}`.
 */
class JsdSolverParseCvParamsTest {

    private val solver = JsdSolver(
        client = okhttp3.OkHttpClient(),
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    )

    @Test
    fun `parseCvParams extracts a, s, h from standard JSON`() {
        val raw = """{"a":"abc123def","s":"/cdn-cgi/challenge-platform/scripts/jsd/main.js","h":"def45678"}"""

        val params = solver.parseCvParams(raw)

        assertNotNull(params)
        assertEquals("abc123def", params.a)
        assertEquals("/cdn-cgi/challenge-platform/scripts/jsd/main.js", params.s)
        assertEquals("def45678", params.h)
    }

    @Test
    fun `parseCvParams handles single-quoted JS object`() {
        val raw = """{'a':'abc123','s':'/cdn-cgi/jsd/main.js','h':'abc12345'}"""

        val params = solver.parseCvParams(raw)

        assertNotNull(params)
        assertEquals("abc123", params.a)
        assertEquals("/cdn-cgi/jsd/main.js", params.s)
        assertEquals("abc12345", params.h)
    }

    @Test
    fun `parseCvParams handles unquoted keys`() {
        val raw = """{a:"abc123def",s:"/cdn-cgi/jsd/main.js",h:"def45678"}"""

        val params = solver.parseCvParams(raw)

        assertNotNull(params)
        assertEquals("abc123def", params.a)
        assertEquals("/cdn-cgi/jsd/main.js", params.s)
        assertEquals("def45678", params.h)
    }

    @Test
    fun `parseCvParams derives h from a when h is missing`() {
        val raw = """{"a":"abc123def","s":"/cdn-cgi/jsd/main.js"}"""

        val params = solver.parseCvParams(raw)

        assertNotNull(params)
        assertEquals("abc123def", params.a)
        assertEquals("/cdn-cgi/jsd/main.js", params.s)
        assertEquals("bc123def", params.h)  // "abc123def".takeLast(8) = "bc123def"
    }

    @Test
    fun `parseCvParams deriveH from a shorter than 8 chars`() {
        val raw = """{"a":"short","s":"/cdn-cgi/jsd/main.js"}"""

        val params = solver.parseCvParams(raw)

        assertNotNull(params)
        assertEquals("short", params.a)
        assertEquals("short", params.h)  // a.takeLast(8) on 5-char string returns the whole string
    }

    @Test
    fun `parseCvParams returns null when a is missing`() {
        val raw = """{"s":"/cdn-cgi/jsd/main.js","h":"def45678"}"""

        val params = solver.parseCvParams(raw)

        assertNull(params)
    }

    @Test
    fun `parseCvParams returns null when s is missing`() {
        val raw = """{"a":"abc123def","h":"def45678"}"""

        val params = solver.parseCvParams(raw)

        assertNull(params)
    }

    @Test
    fun `parseCvParams returns null for empty object`() {
        val params = solver.parseCvParams("{}")

        assertNull(params)
    }

    @Test
    fun `parseCvParams returns null for garbage input`() {
        val params = solver.parseCvParams("not even close to json")

        assertNull(params)
    }

    @Test
    fun `parseCvParams handles extra fields gracefully`() {
        val raw = """{"a":"abc","s":"/cdn-cgi/jsd/main.js","h":"def12345","extra":"value","r":"redirect"}"""

        val params = solver.parseCvParams(raw)

        assertNotNull(params)
        assertEquals("abc", params.a)
        assertEquals("def12345", params.h)
    }

    @Test
    fun `parseCvParams handles r-t variant as fallback`() {
        // The r-t variant would be transformed by ChallengeDetector before
        // reaching JsdSolver, but test the raw parser just in case.
        val raw = """{"r":"abc123def","t":"MTIzNDU2Nzg5MA=="}"""

        // Without a and s, this should return null
        val params = solver.parseCvParams(raw)

        assertNull(params)
    }

    @Test
    fun `parseCvParams handles escaped characters in values`() {
        val raw = """{"a":"test/value","s":"/cdn-cgi/jsd/main.js?v=1.2","h":"deadbeef"}"""

        val params = solver.parseCvParams(raw)

        assertNotNull(params)
        assertEquals("test/value", params.a)
        assertEquals("/cdn-cgi/jsd/main.js?v=1.2", params.s)
    }

    @Test
    fun `parseCvParams handles numeric values`() {
        val raw = """{"a":12345,"s":"/cdn-cgi/jsd/main.js","h":"67890abc"}"""

        val params = solver.parseCvParams(raw)

        assertNotNull(params)
        assertEquals("12345", params.a)
        assertEquals("67890abc", params.h)
    }

    @Test
    fun `parseCvParams handles long challenge token`() {
        val raw = """{"a":"9f285a71c8b3cb7","s":"/cdn-cgi/challenge-platform/scripts/jsd/main.js","h":"8b3cb7"}"""

        val params = solver.parseCvParams(raw)

        assertNotNull(params)
        assertEquals("9f285a71c8b3cb7", params.a)
        assertEquals("8b3cb7", params.h)
    }
}
