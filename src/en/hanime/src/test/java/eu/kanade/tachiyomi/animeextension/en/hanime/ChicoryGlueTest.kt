package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChicoryGlueTest {

    // ── Initial state ───────────────────────────────────────────────────

    @Test
    fun initialState_capturedSignatureIsNull() {
        val glue = ChicoryGlue()
        assertNull(glue.capturedSignature, "capturedSignature must be null on construction")
    }

    @Test
    fun initialState_capturedTimestampIsNull() {
        val glue = ChicoryGlue()
        assertNull(glue.capturedTimestamp, "capturedTimestamp must be null on construction")
    }

    @Test
    fun initialState_eventTypesIsEmpty() {
        val glue = ChicoryGlue()
        assertTrue(glue.eventTypes.isEmpty(), "eventTypes must be empty on construction")
    }

    // ── reset() clears state ───────────────────────────────────────────

    @Test
    fun resetClearsCapturedSignature() {
        val glue = ChicoryGlue()
        // Simulate state that would be set during WASM execution
        // (We can't set these directly since they have private setters,
        //  but reset() should still work even from initial state)
        glue.reset()
        assertNull(glue.capturedSignature, "capturedSignature must be null after reset()")
    }

    @Test
    fun resetClearsCapturedTimestamp() {
        val glue = ChicoryGlue()
        glue.reset()
        assertNull(glue.capturedTimestamp, "capturedTimestamp must be null after reset()")
    }

    @Test
    fun resetIsIdempotent() {
        val glue = ChicoryGlue()
        // Calling reset() multiple times must not throw or corrupt state
        glue.reset()
        glue.reset()
        glue.reset()
        assertNull(glue.capturedSignature)
        assertNull(glue.capturedTimestamp)
    }

    // ── ASM_CONST constants ────────────────────────────────────────────

    @Test
    fun asmConstTimestampValue() {
        assertEquals(17392, ChicoryGlue.ASM_CONST_TIMESTAMP, "ASM_CONST_TIMESTAMP must be 17392")
    }

    @Test
    fun asmConstSignatureValue() {
        assertEquals(17442, ChicoryGlue.ASM_CONST_SIGNATURE, "ASM_CONST_SIGNATURE must be 17442")
    }

    // ── buildHostFunctions returns 25 functions ────────────────────────

    @Test
    fun buildHostFunctionsReturnsExactly25() {
        val glue = ChicoryGlue()
        val functions = glue.buildHostFunctions()
        assertEquals(25, functions.size, "buildHostFunctions() must return exactly 25 HostFunction objects")
    }

    // ── buildHostFunctions all from module "a" ─────────────────────────

    @Test
    fun buildHostFunctionsAllFromModuleA() {
        val glue = ChicoryGlue()
        val functions = glue.buildHostFunctions()
        for (fn in functions) {
            assertEquals("a", fn.module(), "Every HostFunction must have moduleName 'a'")
        }
    }

    // ── buildHostFunctions has all names a-y ───────────────────────────

    @Test
    fun buildHostFunctionsHasAllNamesAThroughY() {
        val glue = ChicoryGlue()
        val functions = glue.buildHostFunctions()
        val names = functions.map { it.name() }.toSet()
        val expectedNames = ('a'..'y').map { it.toString() }.toSet()
        assertEquals(expectedNames, names, "HostFunction names must be 'a' through 'y'")
    }

    @Test
    fun buildHostFunctionsNoDuplicateNames() {
        val glue = ChicoryGlue()
        val functions = glue.buildHostFunctions()
        val names = functions.map { it.name() }
        assertEquals(names.size, names.toSet().size, "HostFunction names must not contain duplicates")
    }

    // ── ASM_CONSTS timestamp handler (indirect test) ──────────────────

    @Test
    fun buildHostFunctionsContainsGFunctionForAsmConsts() {
        val glue = ChicoryGlue()
        val functions = glue.buildHostFunctions()
        val gFunction = functions.find { it.name() == "g" }
        assertNotNull(gFunction, "HostFunction 'g' (ASM_CONSTS bridge) must exist")
        assertEquals("a", gFunction.module(), "HostFunction 'g' must be from module 'a'")
    }

    @Test
    fun gFunctionHasCorrectSignature() {
        val glue = ChicoryGlue()
        val functions = glue.buildHostFunctions()
        val gFunction = functions.find { it.name() == "g" }
        assertNotNull(gFunction, "HostFunction 'g' must exist")

        // The 'g' function is _emscripten_asm_const_int — the ASM_CONSTS bridge.
        // WASM import signature: (i32, i32, i32) -> (i32)
        // args[0] = ASM_CONSTS integer ID, args[1..2] = handler-specific arguments
        assertEquals(3, gFunction.paramTypes().size, "HostFunction 'g' must take 3 parameters")
        assertEquals(
            com.dylibso.chicory.wasm.types.ValType.I32,
            gFunction.paramTypes()[0],
            "HostFunction 'g' first param must be I32",
        )
        assertEquals(
            com.dylibso.chicory.wasm.types.ValType.I32,
            gFunction.paramTypes()[1],
            "HostFunction 'g' second param must be I32",
        )
        assertEquals(
            com.dylibso.chicory.wasm.types.ValType.I32,
            gFunction.paramTypes()[2],
            "HostFunction 'g' third param must be I32",
        )
        assertEquals(1, gFunction.returnTypes().size, "HostFunction 'g' must return 1 value")
        assertEquals(
            com.dylibso.chicory.wasm.types.ValType.I32,
            gFunction.returnTypes()[0],
            "HostFunction 'g' return must be I32",
        )
    }

    // ── buildHostFunctions is reproducible ─────────────────────────────

    @Test
    fun buildHostFunctionsIsReproducibleCount() {
        val glue = ChicoryGlue()
        val first = glue.buildHostFunctions()
        val second = glue.buildHostFunctions()
        assertEquals(first.size, second.size, "Repeated calls must return the same count")
    }

    @Test
    fun buildHostFunctionsIsReproducibleNames() {
        val glue = ChicoryGlue()
        val first = glue.buildHostFunctions()
        val second = glue.buildHostFunctions()
        val firstNames = first.map { it.name() }.sorted()
        val secondNames = second.map { it.name() }.sorted()
        assertEquals(firstNames, secondNames, "Repeated calls must return the same names in order")
    }

    @Test
    fun buildHostFunctionsIsReproducibleModules() {
        val glue = ChicoryGlue()
        val first = glue.buildHostFunctions()
        val second = glue.buildHostFunctions()
        val firstModules = first.map { it.module() }.sorted()
        val secondModules = second.map { it.module() }.sorted()
        assertEquals(firstModules, secondModules, "Repeated calls must return the same modules")
    }
}
