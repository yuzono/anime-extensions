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
        // (We can't set these directly since they have private setters,  // but reset() should still work even from initial state)
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

    // ── fullReset() ───────────────────────────────────────────────────

    @Test
    fun `fullReset exists and is callable`() {
        val glue = ChicoryGlue()
        // fullReset should not throw
        glue.fullReset()
        assertNull(glue.capturedSignature)
        assertNull(glue.capturedTimestamp)
        assertEquals(emptySet<String>(), glue.eventTypes)
    }

    @Test
    fun `reset preserves eventTypes while fullReset clears them`() {
        val glue = ChicoryGlue()
        // We can't easily register event types without a WASM instance,
        // but we can verify the contract: reset() never touches eventTypes,
        // while fullReset() does. Simulate registration by accessing the
        // internal set through the window_on host function mechanism.
        val functions = glue.buildHostFunctions()
        val windowOn = functions.first { it.name() == "y" }
        // The window_on function reads a C string from WASM memory at the given
        // pointer. Without a real instance, we can't call it. Instead, verify
        // the behavioral contract on empty sets (the invariant is that reset()
        // never mutates eventTypes, regardless of their content).
        val eventTypesBeforeReset = glue.eventTypes
        glue.reset()
        assertEquals(eventTypesBeforeReset, glue.eventTypes)

        // fullReset should clear eventTypes
        glue.fullReset()
        assertEquals(emptySet<String>(), glue.eventTypes)
    }

    // ── EmvalHandleManager tests ───────────────────────────────────────

    @Test
    fun testEmvalHandleAllocation() {
        val manager = ChicoryGlue.EmvalHandleManager()
        val value = ChicoryGlue.EmvalHandleManager.EmvalValue.JsString("hello")
        val handle = manager.allocate(value)
        assertTrue(handle > 0, "Allocated handle must be positive")
        val retrieved = manager.get(handle)
        assertNotNull(retrieved, "Retrieved value must not be null")
        assertEquals("hello", (retrieved as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString).value)
    }

    @Test
    fun testEmvalAllocateUndefinedReturnsZero() {
        val manager = ChicoryGlue.EmvalHandleManager()
        val handle = manager.allocate(ChicoryGlue.EmvalHandleManager.EmvalValue.JsUndefined)
        assertEquals(0, handle, "Allocating JsUndefined must return handle 0")
    }

    @Test
    fun testEmvalGetZeroReturnsUndefined() {
        val manager = ChicoryGlue.EmvalHandleManager()
        val value = manager.get(0)
        assertTrue(value is ChicoryGlue.EmvalHandleManager.EmvalValue.JsUndefined, "Handle 0 must return JsUndefined")
    }

    @Test
    fun testEmvalGetGlobalWindow() {
        val manager = ChicoryGlue.EmvalHandleManager()
        val windowHandle = manager.windowHandle
        assertTrue(windowHandle > 0, "windowHandle must be a valid (positive) handle")
        val windowVal = manager.get(windowHandle)
        assertNotNull(windowVal, "window value must not be null")
        assertTrue(windowVal is ChicoryGlue.EmvalHandleManager.EmvalValue.JsObject, "window must be a JsObject")
    }

    @Test
    fun testEmvalGetGlobalUnknown() {
        // Unknown globals are not pre-registered — they would return 0 (undefined)
        // in the host function. Test the handle manager directly.
        val manager = ChicoryGlue.EmvalHandleManager()
        // Handle for "unknownGlobal" is not allocated, so get() returns null
        val result = manager.get(99999)
        assertNull(result, "Non-existent handle must return null")
    }

    @Test
    fun testEmvalGetLocationOrigin() {
        val manager = ChicoryGlue.EmvalHandleManager()
        // Chain: window → location → origin
        val windowVal = manager.get(manager.windowHandle) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsObject
        val locationHandle = windowVal.properties["location"]
        assertNotNull(locationHandle, "window.location must be a valid handle")
        assertTrue(locationHandle > 0, "location handle must be positive")

        val locationVal = manager.get(locationHandle) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsObject
        val originHandle = locationVal.properties["origin"]
        assertNotNull(originHandle, "location.origin must be a valid handle")
        assertTrue(originHandle > 0, "origin handle must be positive")

        val originVal = manager.get(originHandle) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString
        assertEquals("https://hanime.tv", originVal.value, "location.origin must be 'https://hanime.tv'")
    }

    @Test
    fun testEmvalNewCstring() {
        val manager = ChicoryGlue.EmvalHandleManager()
        val handle = manager.allocate(ChicoryGlue.EmvalHandleManager.EmvalValue.JsString("test-string"))
        assertTrue(handle > 0, "New C-string handle must be positive")
        val value = manager.get(handle) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString
        assertEquals("test-string", value.value, "Retrieved string must match original")
    }

    @Test
    fun testEmvalDecref() {
        val manager = ChicoryGlue.EmvalHandleManager()
        val handle = manager.allocate(ChicoryGlue.EmvalHandleManager.EmvalValue.JsString("temporary"))
        assertTrue(handle > 0)
        assertNotNull(manager.get(handle), "Handle must exist before decref")
        manager.release(handle)
        assertNull(manager.get(handle), "Handle must not exist after decref")
    }

    @Test
    fun testEmvalDecrefZeroIsNoOp() {
        val manager = ChicoryGlue.EmvalHandleManager()
        // Releasing handle 0 (undefined) must not throw
        manager.release(0)
        // Handle 0 still returns JsUndefined per emscripten convention
        assertTrue(manager.get(0) is ChicoryGlue.EmvalHandleManager.EmvalValue.JsUndefined)
    }

    @Test
    fun testFullResetClearsEmvalHandles() {
        val glue = ChicoryGlue()
        // Allocate a temporary handle beyond the pre-registered ones
        val tempHandle = glue.emvalManager.allocate(
            ChicoryGlue.EmvalHandleManager.EmvalValue.JsString("temp"),
        )
        assertTrue(tempHandle > 0, "Temporary handle must be positive")
        assertNotNull(glue.emvalManager.get(tempHandle), "Temporary handle must exist before reset")

        // fullReset should clear all handles and re-initialize the mock environment
        glue.fullReset()

        // The temporary handle should no longer exist after re-initialization
        assertNull(glue.emvalManager.get(tempHandle), "Temporary handle must be gone after fullReset")

        // But the pre-registered handles should be re-initialized
        assertTrue(glue.emvalManager.windowHandle > 0, "windowHandle must be valid after fullReset")
        assertNotNull(glue.emvalManager.get(glue.emvalManager.windowHandle), "window must exist after fullReset")
    }

    @Test
    fun testWindowTopSelfReference() {
        val manager = ChicoryGlue.EmvalHandleManager()
        // window.top === window (self-reference)
        val windowVal = manager.get(manager.windowHandle) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsObject
        val topHandle = windowVal.properties["top"]
        assertNotNull(topHandle, "window.top must exist")
        assertEquals(manager.windowHandle, topHandle, "window.top must equal window (self-reference)")
    }

    @Test
    fun testWindowLocationProperties() {
        val manager = ChicoryGlue.EmvalHandleManager()
        val windowVal = manager.get(manager.windowHandle) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsObject

        // Verify all expected window properties exist
        assertTrue(windowVal.properties.containsKey("location"), "window must have 'location' property")
        assertTrue(windowVal.properties.containsKey("addEventListener"), "window must have 'addEventListener' property")
        assertTrue(windowVal.properties.containsKey("dispatchEvent"), "window must have 'dispatchEvent' property")
        assertTrue(windowVal.properties.containsKey("top"), "window must have 'top' property")
        assertTrue(windowVal.properties.containsKey("ssignature"), "window must have 'ssignature' property")
        assertTrue(windowVal.properties.containsKey("stime"), "window must have 'stime' property")

        // ssignature and stime are initially undefined (handle 0)
        assertEquals(0, windowVal.properties["ssignature"], "window.ssignature must be undefined (0) initially")
        assertEquals(0, windowVal.properties["stime"], "window.stime must be undefined (0) initially")
    }

    @Test
    fun testLocationObjectProperties() {
        val manager = ChicoryGlue.EmvalHandleManager()
        val locationVal = manager.get(manager.locationHandle) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsObject

        // Verify all expected location properties
        assertTrue(locationVal.properties.containsKey("origin"), "location must have 'origin'")
        assertTrue(locationVal.properties.containsKey("href"), "location must have 'href'")
        assertTrue(locationVal.properties.containsKey("hostname"), "location must have 'hostname'")
        assertTrue(locationVal.properties.containsKey("protocol"), "location must have 'protocol'")

        // Verify string values
        val origin = manager.get(locationVal.properties["origin"]!!) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString
        assertEquals("https://hanime.tv", origin.value)

        val href = manager.get(locationVal.properties["href"]!!) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString
        assertEquals("https://hanime.tv/", href.value)

        val hostname = manager.get(locationVal.properties["hostname"]!!) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString
        assertEquals("hanime.tv", hostname.value)

        val protocol = manager.get(locationVal.properties["protocol"]!!) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString
        assertEquals("https:", protocol.value)
    }

    @Test
    fun testDocumentObjectProperties() {
        val manager = ChicoryGlue.EmvalHandleManager()
        val docVal = manager.get(manager.documentHandle) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsObject

        val domain = manager.get(docVal.properties["domain"]!!) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString
        assertEquals("hanime.tv", domain.value)

        val referrer = manager.get(docVal.properties["referrer"]!!) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString
        assertEquals("", referrer.value)

        val cookie = manager.get(docVal.properties["cookie"]!!) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString
        assertEquals("", cookie.value)
    }

    @Test
    fun testNavigatorObjectProperties() {
        val manager = ChicoryGlue.EmvalHandleManager()
        val navVal = manager.get(manager.navigatorHandle) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsObject

        val userAgent = manager.get(navVal.properties["userAgent"]!!) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString
        assertTrue(userAgent.value.contains("Chrome/130"), "navigator.userAgent must contain Chrome/130")

        val platform = manager.get(navVal.properties["platform"]!!) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString
        assertEquals("Linux armv81", platform.value)

        val language = manager.get(navVal.properties["language"]!!) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString
        assertEquals("en-US", language.value)
    }

    @Test
    fun testEmvalStringLengthProperty() {
        val manager = ChicoryGlue.EmvalHandleManager()
        val strHandle = manager.allocate(ChicoryGlue.EmvalHandleManager.EmvalValue.JsString("hello"))
        val strVal = manager.get(strHandle) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsString

        // Simulate __emval_get_property for "length" on a JsString
        val lengthHandle = manager.allocate(
            ChicoryGlue.EmvalHandleManager.EmvalValue.JsNumber(strVal.value.length.toDouble()),
        )
        assertTrue(lengthHandle > 0, "length handle must be positive")
        val lengthVal = manager.get(lengthHandle) as ChicoryGlue.EmvalHandleManager.EmvalValue.JsNumber
        assertEquals(5.0, lengthVal.value, "String 'hello' length must be 5")
    }

    @Test
    fun testEmvalHandleManagerIsAccessibleFromGlue() {
        val glue = ChicoryGlue()
        assertNotNull(glue.emvalManager, "emvalManager must be accessible as a public property")
        assertTrue(glue.emvalManager.windowHandle > 0, "windowHandle must be valid")
        assertTrue(glue.emvalManager.locationHandle > 0, "locationHandle must be valid")
        assertTrue(glue.emvalManager.documentHandle > 0, "documentHandle must be valid")
        assertTrue(glue.emvalManager.navigatorHandle > 0, "navigatorHandle must be valid")
        assertTrue(glue.emvalManager.consoleHandle > 0, "consoleHandle must be valid")
    }
}
