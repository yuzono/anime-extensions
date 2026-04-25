package eu.kanade.tachiyomi.animeextension.en.hanime

import android.util.Log
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.ValType
import java.util.concurrent.ConcurrentHashMap

/**
 * Chicory-based WASM glue layer for hanime.tv signature generation.
 *
 * Provides 25 host function imports that the emscripten-compiled WASM binary
 * expects from module "a". Each import becomes a Chicory [HostFunction] that
 * either no-ops, stubs, or performs real work (ASM_CONSTS bridge, heap resize,
 * event listener registration).
 *
 * The two mission-critical imports are:
 * - **g** (_emscripten_asm_const_int): the JS↔WASM bridge through which the
 *   binary communicates signatures and timestamps via the ASM_CONSTS table.
 * - **y** (window_on): registers event listeners; the interpreter dispatches
 *   events to trigger signature computation.
 *
 * The embind/emval functions (a–f, h–n, p–r) are called during initRuntime()
 * but do not need real implementations for signature generation. The environment
 * functions (s, t, u) report no environment. The heap resizer (w) delegates to
 * [Instance.memory]. The error stubs (v, x) throw immediately — fail fast,
 * fail loud.
 */
class ChicoryGlue {

    @Volatile var capturedSignature: String? = null
        private set

    @Volatile var capturedTimestamp: Long? = null
        private set

    private val registeredEventTypes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Immutable snapshot of event types registered via import "y" (window_on). */
    val eventTypes: Set<String> get() = registeredEventTypes.toSet()

    // ASM_CONSTS dispatch table — maps integer IDs to handler lambdas.
    // Each handler receives a LongArray of parsed arguments (matching JS readEmAsmArgs)
    // and the Instance, returning a Long result.
    private val asmConsts = mutableMapOf<Int, (LongArray, Instance) -> Long>()

    init {
        // ID 17392: Returns current Unix timestamp in seconds
        // JS: () => parseInt(new Date().getTime() / 1e3)
        asmConsts[ASM_CONST_TIMESTAMP] = { _, _ ->
            System.currentTimeMillis() / 1000L
        }

        // ID 17442: Captures signature string + timestamp from WASM
        // JS: ($0, $1) => { window.ssignature = UTF8ToString($0); window.stime = $1; }
        // $0 = pointer to signature string, $1 = timestamp integer
        asmConsts[ASM_CONST_SIGNATURE] = { args, instance ->
            val sigPtr = args[0].toInt()
            val timestamp = if (args.size > 1) args[1] else 0L
            capturedSignature = instance.memory().readCString(sigPtr)
            capturedTimestamp = timestamp
            0L
        }
    }

    /**
     * Parse the emscripten ASM_CONSTS argument buffer, mirroring the JS `readEmAsmArgs`.
     *
     * Signature characters:
     * - 'p' = pointer (I32, 4 bytes, aligned to 4)
     * - 'i' = int     (I32, 4 bytes, aligned to 4)
     * - 'j' = i64     (I64, 8 bytes, aligned to 8)
     * - 'd' = double  (F64, 8 bytes, aligned to 8)
     *
     * Non-'p'/'i' chars are "wide" (8 bytes) and get 8-byte alignment.
     * 'p' and 'i' are narrow (4 bytes) with 4-byte alignment.
     */
    private fun readEmAsmArgs(sigPtr: Int, argBuf: Int, instance: Instance): LongArray {
        val result = mutableListOf<Long>()
        val mem = instance.memory()
        var buf = argBuf
        var offset = 0

        // Read signature string character-by-character from WASM memory
        var iterations = 0
        while (iterations < MAX_SIGNATURE_LENGTH) {
            val ch = mem.readU8(sigPtr + offset).toInt().toChar()
            if (ch == '\u0000') break // null terminator
            iterations++
            offset++

            val wide = ch != 'i' && ch != 'p'
            // Align to 8 for wide types
            if (wide && buf % 8 != 0) {
                buf += 4
            }

            result.add(
                when (ch) {
                    'p' -> mem.readI32(buf) and 0xFFFFFFFFL // unsigned I32 pointer
                    'i' -> mem.readI32(buf) // signed I32
                    'j' -> mem.readI64(buf) // I64
                    'd' -> {
                        // F64 bits — read raw 8 bytes as Long for bit-exact representation
                        val low = mem.readI32(buf) and 0xFFFFFFFFL
                        val high = mem.readI32(buf + 4) and 0xFFFFFFFFL
                        (high shl 32) or low
                    }
                    else -> 0L // unknown type char — skip with 0
                },
            )
            buf += if (wide) 8 else 4
        }

        return result.toLongArray()
    }

    /** Reset captured state before a new signature generation cycle. */
    fun reset() {
        capturedSignature = null
        capturedTimestamp = null
        registeredEventTypes.clear()
    }

    /**
     * Build all 25 [HostFunction] imports for module "a".
     *
     * IMPORTANT: The [FunctionType] for each binding MUST exactly match what the
     * WASM binary declares in its import section. If there's a mismatch, Chicory
     * will throw at instantiation time with a clear error message — adjust the
     * FunctionType accordingly.
     */
    fun buildHostFunctions(): List<HostFunction> {
        val module = "a"
        val functions = mutableListOf<HostFunction>()

        // ═══ a = embind type registration (i32, i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "a",
            FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), emptyList()),
        ) { _, _ -> null }

        // ═══ b = embind type registration (i32, i32, i32, i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "b",
            FunctionType.of(
                listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                emptyList(),
            ),
        ) { _, _ -> null }

        // ═══ c = __emval_decref (i32) -> () ═══
        functions += HostFunction(
            module,
            "c",
            FunctionType.of(listOf(ValType.I32), emptyList()),
        ) { _, _ -> null }

        // ═══ d = embind type registration (i32, i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "d",
            FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), emptyList()),
        ) { _, _ -> null }

        // ═══ e = embind type registration (i32, i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "e",
            FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), emptyList()),
        ) { _, _ -> null }

        // ═══ f = embind type registration (i32, i32, i32, i64, i64) -> () ═══
        functions += HostFunction(
            module,
            "f",
            FunctionType.of(
                listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I64, ValType.I64),
                emptyList(),
            ),
        ) { _, _ -> null }

        // ═══ g = _emscripten_asm_const_int — CRITICAL ASM_CONSTS BRIDGE ═══
        // This is how the WASM binary communicates with the JS world.
        // Signature: (i32, i32, i32) -> (i32)
        // args[0] = code  — ASM_CONSTS integer ID
        // args[1] = sigPtr — pointer to signature string (e.g. "pi" = pointer + int)
        // args[2] = argbuf — pointer to argument buffer in WASM memory
        //
        // Mirrors the JS: _emscripten_asm_const_int = (code, sigPtr, argbuf) =>
        //   runEmAsmFunction(code, sigPtr, argbuf)
        // where runEmAsmFunction reads the signature, parses argbuf, then
        // dispatches to ASM_CONSTS[code](...parsedArgs).
        functions += HostFunction(
            module,
            "g",
            FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
        ) { instance, args ->
            val code = args[0].toInt()
            val sigPtr = args[1].toInt()
            val argBuf = args[2].toInt()

            val handler = asmConsts[code]
            val result = if (handler != null) {
                val parsedArgs = readEmAsmArgs(sigPtr, argBuf, instance)
                handler(parsedArgs, instance)
            } else {
                0L.also { Log.w("ChicoryGlue", "Unknown ASM_CONSTS ID: $code — returning 0") }
            }
            longArrayOf(result)
        }

        // ═══ h = __emval_run_destructors (i32) -> () ═══
        functions += HostFunction(
            module,
            "h",
            FunctionType.of(listOf(ValType.I32), emptyList()),
        ) { _, _ -> null }

        // ═══ i = __emval_invoke (i32, i32, i32, i32, i32) -> (f64) ═══
        // Stub: returns 0.0 as f64 bits
        functions += HostFunction(
            module,
            "i",
            FunctionType.of(
                listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                listOf(ValType.F64),
            ),
        ) { _, _ ->
            // Return 0.0 as f64 — raw long bits of double 0.0 is 0L
            longArrayOf(0L)
        }

        // ═══ j = __emval_incref (i32) -> () ═══
        functions += HostFunction(
            module,
            "j",
            FunctionType.of(listOf(ValType.I32), emptyList()),
        ) { _, _ -> null }

        // ═══ k = __emval_create_invoker (i32, i32, i32) -> (i32) ═══
        functions += HostFunction(
            module,
            "k",
            FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
        ) { _, _ -> longArrayOf(0L) }

        // ═══ l = __emval_get_property (i32, i32) -> (i32) ═══
        functions += HostFunction(
            module,
            "l",
            FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
        ) { _, _ -> longArrayOf(0L) }

        // ═══ m = __emval_new_cstring — reads string from memory, returns 0 ═══
        functions += HostFunction(
            module,
            "m",
            FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
        ) { instance, args ->
            // Read the string as a side-effect (exercises memory read path)
            instance.memory().readCString(args[0].toInt())
            longArrayOf(0L)
        }

        // ═══ n = embind destructor (i32) -> () ═══
        functions += HostFunction(
            module,
            "n",
            FunctionType.of(listOf(ValType.I32), emptyList()),
        ) { _, _ -> null }

        // ═══ o = embind type registration (i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "o",
            FunctionType.of(listOf(ValType.I32, ValType.I32), emptyList()),
        ) { _, _ -> null }

        // ═══ p = __emval_get_global (i32) -> (i32) ═══
        functions += HostFunction(
            module,
            "p",
            FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
        ) { _, _ -> longArrayOf(0L) }

        // ═══ q = embind type registration (i32, i32, i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "q",
            FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32), emptyList()),
        ) { _, _ -> null }

        // ═══ r = embind type registration (i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "r",
            FunctionType.of(listOf(ValType.I32, ValType.I32), emptyList()),
        ) { _, _ -> null }

        // ═══ ENVIRONMENT / TIMEZONE ═══

        // s = __tzset_js (i32, i32, i32, i32) -> () — no-op
        functions += HostFunction(
            module,
            "s",
            FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32), emptyList()),
        ) { _, _ -> null }

        // t = _environ_get (i32, i32) -> (i32) — returns 0 (success)
        functions += HostFunction(
            module,
            "t",
            FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
        ) { _, _ -> longArrayOf(0L) }

        // u = _environ_sizes_get (i32, i32) -> (i32) — writes 0 to both pointers
        functions += HostFunction(
            module,
            "u",
            FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
        ) { instance, args ->
            instance.memory().writeI32(args[0].toInt(), 0) // 0 env vars
            instance.memory().writeI32(args[1].toInt(), 0) // 0 buffer size
            longArrayOf(0L)
        }

        // ═══ ERROR STUBS — throw on invocation ═══

        // v = __abort_js () -> ()
        functions += HostFunction(
            module,
            "v",
            FunctionType.of(emptyList(), emptyList()),
        ) { _, _ ->
            throw RuntimeException(
                "Emscripten error stub 'v' (__abort_js) called — WASM execution error",
            )
        }

        // ═══ HEAP MANAGEMENT ═══

        // w = _emscripten_resize_heap (i32) -> (i32) — grow memory if needed
        functions += HostFunction(
            module,
            "w",
            FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
        ) { instance, args ->
            val requestedSize = args[0].toInt()
            val currentPages = instance.memory().pages()
            val neededPages = (requestedSize + 65535) / 65536
            if (neededPages <= currentPages) {
                longArrayOf(1L) // already enough memory
            } else {
                val delta = neededPages - currentPages
                val result = instance.memory().grow(delta)
                longArrayOf(if (result >= 0) 1L else 0L) // 1 = success, 0 = failure
            }
        }

        // ═══ x = ___cxa_throw (i32, i32, i32) -> () — error stub ═══
        functions += HostFunction(
            module,
            "x",
            FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), emptyList()),
        ) { _, args ->
            throw RuntimeException(
                "Emscripten error stub 'x' (___cxa_throw) called — WASM execution error. Args: ${args.toList()}",
            )
        }

        // ═══ EVENT LISTENER REGISTRATION ═══

        // y = window_on (i32) -> () — reads event type string, records it
        functions += HostFunction(
            module,
            "y",
            FunctionType.of(listOf(ValType.I32), emptyList()),
        ) { instance, args ->
            val eventTypePtr = args[0].toInt()
            val eventType = instance.memory().readCString(eventTypePtr)
            registeredEventTypes.add(eventType)
            null
        }

        return functions
    }

    companion object {
        const val ASM_CONST_TIMESTAMP = 17392
        const val ASM_CONST_SIGNATURE = 17442

        /** Maximum signature string length to prevent infinite loops on corrupted memory. */
        private const val MAX_SIGNATURE_LENGTH = 32
    }
}
