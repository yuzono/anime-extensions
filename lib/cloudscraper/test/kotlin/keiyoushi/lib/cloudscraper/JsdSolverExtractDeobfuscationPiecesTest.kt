package keiyoushi.lib.cloudscraper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [JsdSolver.extractDeobfuscationPieces].
 *
 * This is the most critical path in the JSD solver: if the regex fails to
 * extract the D-array function, R-function, and rotation call from the
 * JSD sensor script, the whole `resolveAndPrepopulate` phase is skipped.
 * Without prepopulation, the script will likely fail to produce a sensor
 * payload because CF's obfuscated property lookups resolve to undefined.
 *
 * The tests cover the three known Cloudflare JSD variants:
 * 1. **Standard** — `function D(jW){return jW=\`...\`.split(';')}`
 * 2. **Phi variant** — `function G(FB){FB=\`...\`.split(';')}`
 * 3. **E variant** — `function E(IZ){IZ=\`...\`.split(';')}`
 *
 * The D_QUANDRY variant from early 2024 uses `const` syntax and is handled
 * separately. All variants use:
 * - A D-function (returns array from delimited string)
 * - An R-function (number rotation: `function W(jW,cW){return jW=jW-355,...}`)
 * - A rotation call wrapping D and checksum
 */
class JsdSolverExtractDeobfuscationPiecesTest {

    private val solver = JsdSolver(
        client = okhttp3.OkHttpClient(),
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    )

    // ── Standard variant ──────────────────────────────────────────────

    @Test
    fun `extractDeobfuscationPieces handles standard D R rotation`() {
        val script = buildScript(
            dFunc = """function D(jW){return jW=`zero;one;two;three;four;five;six;seven`.split(';')}""",
            rFunc = """function W(jW,cW){return jW=jW-355, D()}"""
                .let { "$it(/* param */)" },
            dName = "D",
            offset = 355,
            checksum = 7,
        )

        val pieces = solver.extractDeobfuscationPieces(script)

        assertNotNull(pieces) { "Must extract pieces from standard variant" }
        assertEquals("D", pieces.dName)
        assertEquals("zero;one;two;three;four;five;six;seven", pieces.dString)
        assertEquals(355, pieces.offset)
        assertTrue(pieces.rFuncDecl.contains("function W"), "R-function should be named W")
        assertTrue(pieces.rotationCall.contains("D"), "rotation call should reference D")
        assertTrue(pieces.rotationCall.contains("7"), "rotation call should contain checksum")
    }

    @Test
    fun `extractDeobfuscationPieces handles R with different offset`() {
        val script = buildScript(
            dFunc = """function D(param){return param=`a;b;c;d;e`.split(';')}""",
            rFunc = """function R(x,y){return x=x-108, D()}"""
                .let { "$it(/* param */)" },
            dName = "D",
            offset = 108,
            checksum = 5,
        )

        val pieces = solver.extractDeobfuscationPieces(script)

        assertNotNull(pieces)
        assertEquals("R", pieces.rName)
        assertEquals(108, pieces.offset)
    }

    @Test
    fun `extractDeobfuscationPieces handles Phi variant with G function`() {
        val script = buildScript(
            dFunc = """function G(FB){return FB=`alpha;beta;gamma;delta`.split(';')}""",
            rFunc = """function c(x,y){return x=x-200, G()}"""
                .let { "$it(/* param */)" },
            dName = "G",
            offset = 200,
            checksum = 4,
        )

        val pieces = solver.extractDeobfuscationPieces(script)

        assertNotNull(pieces) { "Must extract pieces from Phi variant" }
        assertEquals("G", pieces.dName)
        assertEquals(200, pieces.offset)
    }

    @Test
    fun `extractDeobfuscationPieces handles E variant`() {
        val script = buildScript(
            dFunc = """function E(IZ){return IZ=`x;y;z`.split(';')}""",
            rFunc = """function W(a,b){return a=a-50, E()}"""
                .let { "$it(/* param */)" },
            dName = "E",
            offset = 50,
            checksum = 3,
        )

        val pieces = solver.extractDeobfuscationPieces(script)

        assertNotNull(pieces) { "Must extract pieces from E variant" }
        assertEquals("E", pieces.dName)
        assertEquals(50, pieces.offset)
    }

    // ── Backtick separator variants ───────────────────────────────────

    @Test
    fun `extractDeobfuscationPieces handles backtick separator`() {
        val script = buildScript(
            dFunc = """function D(jW){return jW=`zero;one;two`.split(';')}""",
            rFunc = """function W(jW,cW){return jW=jW-100, D()}"""
                .let { "$it(/* param */)" },
            dName = "D",
            offset = 100,
            checksum = 3,
        )

        val pieces = solver.extractDeobfuscationPieces(script)

        assertNotNull(pieces)
        assertEquals("zero;one;two", pieces.dString)
    }

    // ── Large D-array (realistic size) ────────────────────────────────

    @Test
    fun `extractDeobfuscationPieces handles large D-array with many entries`() {
        val entries = (0..99).joinToString(";") { "entry$it" }
        val script = buildScript(
            dFunc = """function D(jW){return jW=`$entries`.split(';')}""",
            rFunc = """function W(jW,cW){return jW=jW-355, D()}"""
                .let { "$it(/* param */)" },
            dName = "D",
            offset = 355,
            checksum = 100,
        )

        val pieces = solver.extractDeobfuscationPieces(script)

        assertNotNull(pieces)
        assertEquals(entries, pieces.dString)
        assertEquals("W", pieces.rName)
        assertEquals(355, pieces.offset)
        // Verify the rotation call's checksum references the D-function
        assertTrue(pieces.rotationCall.contains("D,100") || pieces.rotationCall.contains("D, 100"))
    }

    // ── Negative tests ────────────────────────────────────────────────

    @Test
    fun `extractDeobfuscationPieces returns null when no D-function`() {
        val script = """
            function helper(x){return x+1;}
            function W(jW,cW){return jW=jW-355, helper()};
            var result = W(500,0);
        """.trimIndent()

        val pieces = solver.extractDeobfuscationPieces(script)

        assertNull(pieces)
    }

    @Test
    fun `extractDeobfuscationPieces returns null when D-function has no R-function`() {
        val script = """
            function D(jW){return jW=`a;b;c`.split(';')}
            function unrelated(x){return x+1;}
            (function(a,b){return a+b})(D,3);
        """.trimIndent()

        val pieces = solver.extractDeobfuscationPieces(script)

        assertNull(pieces)
    }

    @Test
    fun `extractDeobfuscationPieces returns null for empty script`() {
        val pieces = solver.extractDeobfuscationPieces("")
        assertNull(pieces)
    }

    @Test
    fun `extractDeobfuscationPieces returns null for script with no function`() {
        val pieces = solver.extractDeobfuscationPieces("var x = 1;")
        assertNull(pieces)
    }

    @Test
    fun `extractDeobfuscationPieces returns null when D-function returns empty string`() {
        val script = """function D(jW){return jW=``.split(';')}
            function W(jW,cW){return jW=jW-100, D()}
        """
        // Need a rotation call too for real extraction

        val pieces = solver.extractDeobfuscationPieces(script)
        // Without rotation call, should still extract D but R search won't find a reference
        // The R-function regex requires the D name in the body after the offset
        assertNull(pieces)
    }

    // ── Full realistic JSD script structure ───────────────────────────

    @Test
    fun `extractDeobfuscationPieces handles realistic JSD wrapper`() {
        val dArray = (0..49).joinToString(";") { "prop$it" }
        val script = """
            (function(){
                function D(jW){return jW=`$dArray`.split(';')}
                function W(jW,cW){return jW=jW-355, D()}
                var _cf_chl_opt = {JPsB0:'b'};
                window._cf_chl_opt = _cf_chl_opt;
                (function(){
                    var _c1 = W, _c2 = D;
                    // ... sensor body ...
                    var props = _c2();
                    for(var i=0;i<props.length;i++){
                        window[props[i]] = {};
                    }
                })();
            })();
            (function(a,b){return a-b})(D, 50);
        """.trimIndent()

        val pieces = solver.extractDeobfuscationPieces(script)

        assertNotNull(pieces) { "Must extract from realistic script wrapper" }
        assertEquals("D", pieces.dName)
        assertEquals(355, pieces.offset)
        assertTrue(pieces.rotationCall.isNotEmpty())
    }

    // ── Helper: build a synthetic JSD script ──────────────────────────

    /**
     * Builds a synthetic JSD script with the given D-function, R-function,
     * and rotation call, plus sensor logic and assignment boilerplate.
     */
    private fun buildScript(
        dFunc: String,
        rFunc: String,
        dName: String,
        offset: Int,
        checksum: Int,
    ): String {
        return """
            (function(){
                var _cf_chl_opt = {rt:'b'};
                $dFunc
                $rFunc
                window._cf_chl_opt = _cf_chl_opt;
                /* sensor script body */
                var arr = $dName();
                for (var i = 0; i < arr.length; i++) {
                    var name = arr[i];
                    if (typeof window[name] === 'undefined') {
                        window[name] = {t: 'test', i: 30};
                    }
                }
            })();
            (function(a,b){
                var _d = a();
                var _r = b;
                return _d.length - _r;
            })($dName, $checksum);
        """.trimIndent()
    }
}
