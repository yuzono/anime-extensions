package eu.kanade.tachiyomi.animeextension.en.hanime

import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.MemoryLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Signature provider that uses the Chicory WASM runtime to execute
 * the hanime.tv emscripten-compiled WASM binary for signature generation.
 *
 * Replaces [WasmSignatureProvider] with the production-grade Chicory runtime,
 * eliminating the custom ~5600-line WASM interpreter.
 */
class ChicorySignatureProvider(
    private val wasmBinary: ByteArray,
) : SignatureProvider {

    override val name: String = "ChicoryInterpreter"

    private var instance: Instance? = null
    private var glue: ChicoryGlue? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isClosed = false

    /** Guards concurrent initialization attempts in [getSignature]. */
    private val initMutex = Mutex()

    /** Initialize the WASM runtime. Called internally by [getSignature]. */
    private fun initialize() {
        if (isInitialized) return

        try {
            // 1. Parse the WASM binary
            val module = Parser.parse(wasmBinary)

            // 2. Create the glue layer with host function bindings
            glue = ChicoryGlue()
            val hostFunctions = glue!!.buildHostFunctions()

            // 3. Build import values
            val imports = ImportValues.builder()
                .addFunction(*hostFunctions.toTypedArray())
                .build()

            // 4. Instantiate the WASM module
            // - ByteBufferMemory (Android-safe, pure Java NIO)
            // - Initialize globals, data segments, element segments
            // - Do NOT auto-call _start (we call exports manually)
            instance = Instance.builder(module)
                .withImportValues(imports)
                .withInitialize(true)
                .withStart(false)
                .withMemoryLimits(MemoryLimits(258, 65536))
                .withMemoryFactory { ByteBufferMemory(it) }
                .build()

            // 5. Memory growth is handled by the WASM binary's _emscripten_resize_heap
            //    import, which delegates to instance.memory().grow() as needed.

            // 6. Call initRuntime() — export "A"
            try {
                instance!!.export("A").apply()
            } catch (e: ChicoryException) {
                throw SignatureException("WASM trap during initRuntime (export A): ${e.message}", e)
            } catch (_: Exception) {
                // Export may not exist in all binary versions — non-fatal
            }

            // 7. Call _main() — export "C"
            // This registers the "e" event listener via import "y" (window_on)
            try {
                instance!!.export("C").apply(0L, 0L) // argc=0, argv=0
            } catch (e: ChicoryException) {
                throw SignatureException("WASM trap during _main (export C): ${e.message}", e)
            } catch (_: Exception) {
                // Export may not exist in all binary versions — non-fatal
            }

            isInitialized = true
        } catch (e: Exception) {
            close()
            throw SignatureException("Failed to initialize Chicory WASM runtime: ${e.message}", e)
        }
    }

    /**
     * Attempt to re-initialize the WASM runtime after a failure.
     * Tears down the current instance and rebuilds from scratch.
     */
    private suspend fun reinitialize() {
        withContext(Dispatchers.Default) {
            initMutex.withLock {
                // Tear down existing instance
                isInitialized = false
                instance = null
                glue?.fullReset()
                glue = null
                if (!isClosed) {
                    initialize()
                }
            }
        }
    }

    override suspend fun getSignature(): Signature {
        if (isClosed) {
            throw SignatureException("Cannot generate signature — provider has been closed")
        }
        initMutex.withLock {
            if (!isInitialized) {
                withContext(Dispatchers.Default) { initialize() }
            }
        }

        return withContext(Dispatchers.Default) {
            val currentInstance = instance ?: throw SignatureException("WASM instance unavailable — provider may have been closed")
            val currentGlue = glue ?: throw SignatureException("WASM glue unavailable — provider may have been closed")

            try {
                generateSignature(currentInstance, currentGlue)
            } catch (e: SignatureException) {
                // If the WASM instance may be in a bad state, try re-initializing once
                if (isClosed) throw e
                try {
                    reinitialize()
                    val newInstance = instance
                        ?: throw SignatureException("WASM instance unavailable after re-initialization")
                    val newGlue = glue
                        ?: throw SignatureException("WASM glue unavailable after re-initialization")
                    generateSignature(newInstance, newGlue)
                } catch (retryEx: SignatureException) {
                    throw SignatureException(
                        "Signature generation failed after re-initialization: ${retryEx.message}",
                        retryEx,
                    )
                }
            }
        }
    }

    /**
     * Generate a signature using the given WASM instance and glue layer.
     * This is the core computation extracted for retry support.
     */
    private fun generateSignature(currentInstance: Instance, currentGlue: ChicoryGlue): Signature {
        try {
            currentGlue.reset()
            val memory = currentInstance.memory()

            // Allocate strings in WASM memory using the binary's own malloc (export "E")
            var eventTypePtr = 0
            var eventJsonPtr = 0
            var useMalloc = false

            try {
                val malloc = currentInstance.export("E")
                eventTypePtr = malloc.apply(2L)[0].toInt()
                if (eventTypePtr == 0) {
                    throw SignatureException("WASM malloc returned null pointer for event type string")
                }
                eventJsonPtr = malloc.apply(3L)[0].toInt()
                if (eventJsonPtr == 0) {
                    throw SignatureException("WASM malloc returned null pointer for event JSON string")
                }
                useMalloc = true
            } catch (e: SignatureException) {
                throw e
            } catch (e: Exception) {
                throw SignatureException("WASM module does not export required malloc function (export E): ${e.message}", e)
            }

            try {
                // Write the event type and JSON into WASM memory
                memory.writeCString(eventTypePtr, "e")
                memory.writeCString(eventJsonPtr, "{}")

                // Call _on_window_event(eventTypePtr, eventJsonPtr) — export "B"
                currentInstance.export("B").apply(
                    eventTypePtr.toLong(),
                    eventJsonPtr.toLong(),
                )
            } finally {
                // Free allocated memory if we used malloc
                if (useMalloc) {
                    try {
                        val free = currentInstance.export("F")
                        free.apply(eventTypePtr.toLong())
                        free.apply(eventJsonPtr.toLong())
                    } catch (_: Exception) {
                        // Free failure is non-fatal
                    }
                }
            }

            // Read captured signature and timestamp from the glue layer
            val signature = currentGlue.capturedSignature
                ?: throw SignatureException("WASM execution did not produce a signature")
            val timestamp = currentGlue.capturedTimestamp
                ?: throw SignatureException("WASM execution did not produce a timestamp")

            // Validate the signature before returning — a corrupted or stale
            // signature would cause 401 errors on the manifest endpoint.
            return Signature(signature, timestamp.toString()).also { it.validate() }
        } catch (e: SignatureException) {
            throw e
        } catch (e: Exception) {
            throw SignatureException("WASM signature generation failed: ${e.message}", e)
        }
    }

    override fun close() {
        // Mark closed first to prevent new getSignature() calls from proceeding.
        // This must happen-before the field nulling below.
        isClosed = true
        isInitialized = false

        // Null out heavy resources without acquiring the mutex.
        // If a getSignature() call is in progress, it holds its own references
        // on the stack and will complete (or fail on next attempt).
        instance = null
        glue?.fullReset()
        glue = null
    }
}
