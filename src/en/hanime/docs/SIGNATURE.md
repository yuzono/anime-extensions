# hanime.tv Signature Generation Documentation

> **Version 2.0** — Major rewrite based on full WASM binary extraction and disassembly.
> All items corrected from prior versions are marked with **[CORRECTED]**.

This document provides comprehensive documentation of the signature authentication system
used by hanime.tv for API requests, based on reverse engineering of the WASM binary,
JS bundles, and live browser behavior.

---

## Table of Contents

1. [Overview](#overview)
2. [WASM Loading Mechanism](#wasm-loading-mechanism)
3. [WASM Export Table](#wasm-export-table)
4. [WASM Import Table](#wasm-import-table)
5. [ASM_CONSTS — The JS↔WASM Bridge](#asm_consts--the-jswasm-bridge)
6. [Event-Driven Signature Generation Flow](#event-driven-signature-generation-flow)
7. [HTTP Header Construction](#http-header-construction)
8. [WASM Memory Layout](#wasm-memory-layout)
9. [Signature Lifecycle](#signature-lifecycle)
10. [The Signature Format](#the-signature-format)
11. [Required Headers](#required-headers)
12. [Data Section Findings](#data-section-findings)
13. [Architectural Summary](#architectural-summary)
14. [Alternative Implementation Approaches](#alternative-implementation-approaches)
15. [Troubleshooting](#troubleshooting)
16. [JS Bundle Distribution](#js-bundle-distribution)
17. [Next Steps for Algorithm Extraction](#next-steps-for-algorithm-extraction)
18. [File References](#file-references)
19. [Version History](#version-history)

---

## Overview

hanime.tv uses a **WASM-based custom signature algorithm** for API authentication. Every
request to protected endpoints must include a valid signature in the `x-signature` header
along with supporting authentication headers.

### Security Model

| Property | Value |
|----------|-------|
| Algorithm | Custom, implemented entirely inside WASM binary |
| Key Source | **[CORRECTED]** Embedded inside the WASM binary itself (no external fetch) |
| Signature Length | 64 characters (hexadecimal) |
| Timestamp | Unix timestamp in seconds |
| Compiler | Emscripten with embind (C++ with JavaScript binding support) |
| Total Functions | ~586 (24 imported, ~562 locally defined) |
| Indirect Call Table | 323 entries (vtable/embind dispatch) |

### What Was Corrected from Prior Versions

The following prior assumptions have been definitively disproven:

- ~~Key is fetched from `hanime.tv/sign.bin`~~ — **No `sign.bin` fetch exists.** The key is
  embedded inside the WASM binary. **[CORRECTED]**
- ~~Key is 16 bytes ASCII `"0123456701234567"`~~ — **No evidence for this key.** The signing
  key is internal to the WASM. **[CORRECTED]**
- ~~Algorithm is HMAC-SHA256~~ — **Confirmed wrong.** The algorithm runs entirely inside
  the WASM binary and produces a 64-char hex string through an unknown process. **[CORRECTED]**
- ~~WASM binary has not yet been extracted~~ — **Wrong.** The WASM has been fully extracted
  to `docs/wasm_dump/0007b7b6.wasm` (WAT format, 1.55MB). **[CORRECTED]**
- ~~Export B takes `(offset, length)` and is a memory copy function~~ — **Wrong.** Export B
  is `_on_window_event`, the signature writer that processes events. **[CORRECTED]**
- ~~Export E returns pointer 86200~~ — **Wrong.** The literal `86200` does not appear in the
  WAT file. The address was runtime-computed. **[CORRECTED]**
- ~~Memory struct at hardcoded offset 86200~~ — **No hardcoded struct offset.** The stack
  pointer starts at 85904 (0x14F50). Addresses are runtime-computed. **[CORRECTED]**

---

## WASM Loading Mechanism

**[CORRECTED]** The WASM binary is NOT loaded from a separate `.wasm` file. It is **embedded
inline as base64** inside the vendor JS bundle:

```javascript
// From: vendor.0130da3e01eaf5c7d570b6ed1becb5f4.min.js

var wasmBinaryFile;

function findWasmBinary() {
    return base64Decode(
        "AGFzbQEAAAABmwMyYAF/AX9gAn9/AGACf38Bf2ABfwBgA39/fwF/YAZ/f39/f38Bf2AFf39/f38Bf2ADf39/..."
        // Entire WASM binary, base64-encoded, starting with AGFzbQ
    );
}
```

The WASM module is instantiated by the Emscripten glue code in the vendor bundle. No
network request for a `.wasm` file is made — the binary is self-contained within the
JavaScript.

---

## WASM Export Table

**[CORRECTED]** The prior export mapping was wrong. The correct mapping, verified against
the WAT disassembly:

| Export | JS Binding | Purpose |
|--------|-----------|---------|
| `z` | `wasmMemory` | Linear memory (258 pages = 16,908,288 bytes, fixed) |
| `A` | `initRuntime` | embind type registration (C++ static init) |
| `B` | `_on_window_event` | **THE SIGNATURE WRITER** — processes events, computes signature |
| `C` | `_main` | C/C++ `main()` entry point |
| `D` | `___getTypeName` | embind RTTI type name lookup |
| `E` | `_malloc` | Heap memory allocator |
| `F` | `_free` | Heap memory deallocator |
| `G` | `__emscripten_stack_restore` | Stack pointer restore |
| `H` | `__emscripten_stack_alloc` | Stack space allocation |
| `I` | `_emscripten_stack_get_current` | Current stack pointer read |

### Key Export Details

**Export B — `_on_window_event` (function index 535):**

This is the critical function. It is invoked via `Module.ccall()` when a window event is
dispatched. It processes the event internally, calls ASM_CONSTS to obtain a timestamp and
write the signature result, and is the sole path by which `window.ssignature` and
`window.stime` are populated.

**Export C — `_main`:**

The C/C++ `main()` function. On startup, it calls import `y` (`window_on`) to register
event listeners on the `window` object. Specifically, it registers a listener for event
`"e"`, which is the trigger for signature computation.

**Export A — `initRuntime`:**

Runs embind type registration. Calling it twice produces the error
`"Cannot register type 'void' twice"` — this is standard embind behavior, not a bug.

---

## WASM Import Table

The WASM module imports 24 functions from the host module `"a"`:

| Import | JS Function | Purpose |
|--------|-----------|---------|
| `a` | `__embind_register_memory_view` | Register memory view type |
| `b` | `__embind_register_integer` | Register integer types |
| `c` | `__emval_decref` | Decrement emval reference count |
| `d` | `__embind_register_std_wstring` | Register wide string type |
| `e` | `__embind_register_float` | Register float types |
| `f` | `__embind_register_bigint` | Register bigint types |
| `g` | `_emscripten_asm_const_int` | **Execute inline JS from WASM** (signature writer bridge) |
| `h` | `__emval_run_destructors` | Run emval destructors |
| `i` | `__emval_invoke` | Invoke emval function |
| `j` | `__emval_incref` | Increment emval reference count |
| `k` | `__emval_create_invoker` | Create emval invoker |
| `l` | `__emval_get_property` | Get property on emval handle |
| `m` | `__emval_new_cstring` | Create C string emval |
| `n` | `__embind_register_emval` | Register emval type |
| `o` | `__embind_register_std_string` | Register std::string type |
| `p` | `__emval_get_global` | Get global object |
| `r` | `__embind_register_bool` | Register boolean type |
| `s` | `__embind_register_void` | Register void type |
| `t` | `__tzset_js` | Set timezone info |
| `u` | `_environ_get` | Get environment variables |
| `v` | `_emscripten_resize_heap` | Resize heap (always aborts — fixed memory) |
| `w` | `_environ_sizes_get` | Get env variable sizes |
| `x` | `___cxa_throw` | C++ exception throw |
| `y` | `window_on` | Register window event listener |

Import `g` (`_emscripten_asm_const_int`) is the bridge that allows WASM to call back into
JavaScript. This is how the WASM module reads the current timestamp and writes the
signature result — see the next section for details.

Import `v` (`_emscripten_resize_heap`) always aborts because the memory is fixed at
258 pages. Dynamic growth is not supported.

---

## ASM_CONSTS — The JS↔WASM Bridge

The WASM module calls back into JavaScript via import `g` (`_emscripten_asm_const_int`).
The JS side maintains a dispatch table keyed by code offset:

```javascript
var ASM_CONSTS = {
    17392: () => parseInt(new Date().getTime() / 1e3),
    // Get Unix timestamp (seconds)

    17442: ($0, $1) => {
        window.ssignature = UTF8ToString($0);
        // $0 = pointer to WASM memory containing the signature string

        window.stime = $1;
        // $1 = the timestamp integer
    },
};
```

### How It Works

1. **ASM_CONSTS[17392]** — The WASM calls this to get the current Unix timestamp in
   seconds. No arguments are passed; the return value is an integer.

2. **ASM_CONSTS[17442]** — The WASM calls this to write the signature result back to
   JavaScript. Two arguments are passed:
   - `$0` — A pointer into WASM linear memory containing the signature as a
     null-terminated UTF-8 string (64 hex characters)
   - `$1` — The timestamp integer that was used for this signature

The `UTF8ToString($0)` helper reads from WASM linear memory at the given pointer and
decodes the bytes as a UTF-8 string. This is how the 64-character hex signature crosses
the WASM↔JS boundary.

---

## Event-Driven Signature Generation Flow

The signature is generated through an event-driven architecture, not by direct function
calls:

### Step-by-Step Sequence

1. **Page loads** — `vendor.js` creates the WASM instance from the inline base64 binary.

2. **`initRuntime()`** — JS calls export `A()` → embind type registration runs.

3. **`callMain()`** — JS calls export `C()` → `_main()` executes, which calls import `y`
   (`window_on`) to register event listeners on `window`.

4. **Event registration** — `_main()` registers a listener for the custom event named
   `"e"` on the `window` object.

5. **Event fires** — When `"e"` is dispatched, JS calls:
   ```javascript
   Module.ccall("on_window_event", null, ["string", "string"], ["e", JSON.stringify(detail || {})])
   ```
   This enters WASM export B (`_on_window_event`, function index 535).

6. **WASM processes the event** — Inside export B, the WASM:
   - Calls ASM_CONSTS[17392] to get the current Unix timestamp
   - Performs the signature computation (algorithm unknown)
   - Calls ASM_CONSTS[17442] to write results to JavaScript globals

7. **Results written** — After ASM_CONSTS[17442] executes:
   - `window.ssignature` = 64-character hex signature string
   - `window.stime` = Unix timestamp integer

8. **App readiness** — The Vue app polls `window.stime` every 100ms. When truthy, it
   stops polling and the app is ready.

9. **Per-request signing** — On every API request, `Emit("e")` triggers a fresh
   signature computation, then headers are constructed from the current
   `window.ssignature` and `window.stime`.

### The Emit Function

```javascript
window.Emit = function(t) {
    return window.dispatchEvent(new CustomEvent(t));
}
```

This is a simple wrapper around `window.dispatchEvent`. Calling `Emit("e")` triggers the
WASM signature computation.

### The Polling Loop (from 40c99ce.js)

```javascript
// On app mount:
window.stime
    ? this.finalizer()           // already set — app ready
    : (this.stime_poll = setInterval(function() {
        Emit("e"),               // trigger WASM signature computation
        window.stime && (        // check if WASM wrote the result
            t.finalizer(),
            clearInterval(t.stime_poll),
            t.stime_poll = null
        );
    }, 100))
```

The polling loop fires the `"e"` event every 100ms until the WASM module has written a
timestamp to `window.stime`. Once the first signature is produced, the app considers
itself initialized and stops polling.

---

## HTTP Header Construction

**[CORRECTED]** The complete header construction logic, verified from `40c99ce.js`:

```javascript
{
    "content-type":       "application/json",
    "accept":             "application/json",
    "x-session-token":    S.session_token || "",
    "x-user-license":     S.encrypted_user_license || "",
    "x-license":          t.x_license || "",
    "x-signature-version": "web2",           // HARDCODED
    "x-signature":        window.ssignature,  // FROM WASM
    "x-time":             window.stime,       // FROM WASM
    "x-csrf-token":       S.csrf_token || "",
}
```

### Header Notes

- `x-signature-version` is hardcoded as `"web2"` — it is never dynamically computed.
- `x-signature` and `x-time` come directly from the WASM-written globals.
- `x-session-token`, `x-user-license`, `x-csrf-token`, and `x-license` are empty for
  guest requests and populated from the session store for authenticated requests.
- `content-type` and `accept` are always `"application/json"`.

### Guest Request Example

```http
content-type: application/json
accept: application/json
x-session-token:
x-user-license:
x-license:
x-signature-version: web2
x-signature: <64-char hex from window.ssignature>
x-time: <Unix timestamp from window.stime>
x-csrf-token:
```

### Authenticated Request Example

```http
content-type: application/json
accept: application/json
x-session-token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
x-user-license: <encrypted_user_license>
x-license: <license_string>
x-signature-version: web2
x-signature: <64-char hex from window.ssignature>
x-time: <Unix timestamp from window.stime>
x-csrf-token: <csrf_token>
```

---

## WASM Memory Layout

**[CORRECTED]** The prior documentation claimed a hardcoded struct at offset 86200. The
literal `86200` does NOT appear in the WAT file. The address `86200` was a
runtime-computed value observed in the browser, not a constant in the binary.

### Memory Regions

| Region | Address Range | Size | Description |
|--------|--------------|------|-------------|
| Data | `0x0000` — `~0x5000` | ~20KB | Static data (locale, hex table, case maps) |
| Stack | `0x14F50` ↓ (grows down) | ~85,904 bytes | Stack pointer (`$global0`) starts here |
| Heap | Above stack → `0x1010000` | ~16MB | Managed by `_malloc` / `_free` |
| **Total** | `0x0000` — `0x1010000` | 16,908,288 bytes | 258 pages x 64KB, **fixed, no growth** |

### Memory Access from JavaScript

```javascript
// Read from WASM memory
const memory = new Uint8Array(window.wasmMemory.buffer);
const result = memory.slice(offset, offset + length);

// Write to WASM memory
memory.set(data, offset);
```

### Key Points

- The stack grows **downward** from 0x14F50 (85904 decimal).
- Heap allocation is handled by `_malloc` (export E) and `_free` (export F).
- `_emscripten_resize_heap` (import v) always aborts — the 258-page limit is fixed.
- Runtime-computed addresses (like the previously reported `86200`) are determined by
  stack and heap state at execution time, not by constants in the binary.

---

## Signature Lifecycle

### Event-Driven Generation (NOT On-Demand)

The signature is generated through the event system, not by direct function calls:

- `Emit("e")` triggers the WASM signature computation
- The WASM writes results to `window.ssignature` and `window.stime`
- API requests read these globals at request time
- On startup, a 100ms polling loop fires `Emit("e")` until the first signature is ready
- For subsequent API requests, `Emit("e")` is called to produce a fresh signature

### Signature Expiry

- Signatures **expire** — 401 Unauthorized responses occur when using a stale signature
- The exact validity window is not yet determined
- Implementations must refresh signatures before each request or implement periodic refresh
- If a 401 is received, re-emit the event and retry

### Implications for Implementations

- Do NOT assume a signature is valid indefinitely
- Emit the `"e"` event before each API request to get a fresh signature
- Implement retry logic for 401 responses
- The polling loop pattern (fire every 100ms until `window.stime` is set) can be used
  for initialization

---

## The Signature Format

### Signature Characteristics

| Property | Value |
|----------|-------|
| Format | Hexadecimal string |
| Length | 64 characters |
| Bit Length | 256 bits |
| Algorithm | Custom WASM-based (unknown internals) |
| Case | Lowercase |

### Example Signatures

```
99ce0e4c35fea0b69bae9e177c614d224225897adb2b203246e2170ff1f509c5
8567328e24f9cbc47052b338a21f2cb056275388668be224262df46e12a68084
c1c40b42...
```

### Browser-Based Access

```javascript
window.ssignature // "c1c40b42..." — 64-char hex signature, matches x-signature header
window.stime      // 1776643038 — Unix timestamp in seconds, matches x-time header
```

---

## Required Headers

Every API request requires the following authentication headers:

| Header | Type | Guest Value | Description |
|--------|------|-------------|-------------|
| `x-signature` | string | **Required** | 64-char hex signature from WASM |
| `x-time` | integer | **Required** | Unix timestamp in seconds |
| `x-signature-version` | string | `"web2"` | Signature algorithm version (**hardcoded**) |
| `x-session-token` | string | `""` (empty) | Session authentication token |
| `x-user-license` | string | `""` (empty) | User license tier |
| `x-csrf-token` | string | `""` (empty) | CSRF protection token |
| `x-license` | string | `""` (empty) | License verification |

Additionally, all requests include:

| Header | Value |
|--------|-------|
| `content-type` | `application/json` |
| `accept` | `application/json` |

---

## Data Section Findings

Analysis of the WASM binary's 18 data segments reveals:

| Finding | Details |
|---------|---------|
| Data segments | 18 total |
| Hex lookup table | `"0123456789ABCDEF"` at offset 4585 |
| Locale strings | `"C.UTF-8"`, `"LC_CTYPE"`, `"LC_NUMERIC"`, `"LC_TIME"`, etc. |
| Unicode tables | Case mapping tables (toupper/tolower) |
| Domain names | **None found** in WASM data |
| URLs | **None found** in WASM data |
| API endpoints | **None found** in WASM data |
| Signing key | **Not visible** in data section — likely computed or embedded in code section |

The presence of the hex lookup table (`0123456789ABCDEF`) at offset 4585 is significant —
it is used by the signature writer to convert binary hash output to hex characters. The
absence of domain names, URLs, or a plaintext signing key from the data section suggests
that the key is either computed at runtime or embedded in the code section as numeric
constants rather than string data.

---

## Architectural Summary

| Property | Value |
|----------|-------|
| Compiler | Emscripten with embind |
| Language | C++ with JavaScript binding support |
| Total functions | ~586 (24 imported, ~562 locally defined) |
| Indirect call table | 1 table with 323 entries (vtable/embind dispatch) |
| Stack pointer | Global `$global0`, starts at 85904 (0x14F50) |
| Memory | 258 pages (16,908,288 bytes), fixed, no growth |
| Start section | None — init driven by JS glue code |
| Signature writer | Function index 535 (export B = `_on_window_event`) |
| JS↔WASM bridge | `_emscripten_asm_const_int` (import g) with 2 ASM_CONSTS entries |

### Why WASM?

hanime.tv uses WASM for signature generation because it:

- Makes reverse engineering significantly more difficult
- Protects the algorithm from easy inspection in DevTools
- Allows use of cryptographic primitives compiled from C/C++
- Requires either running the actual WASM module, or full disassembly and analysis

---

## Alternative Implementation Approaches

Until the WASM algorithm is fully reverse-engineered, the following approaches can work:

### 1. WebView Approach

Load hanime.tv in a WebView and read `window.ssignature` and `window.stime` directly:

```kotlin
// Android/Aniyomi WebView approach
webView.evaluateJavascript("window.ssignature") { signature ->
    webView.evaluateJavascript("window.stime") { time ->
        val headers = mapOf(
            "x-signature" to signature.trimQuotes(),
            "x-time" to time.trimQuotes(),
            "x-signature-version" to "web2",
            "content-type" to "application/json",
            "accept" to "application/json",
            // ... other headers
        )
    }
}
```

**Pros:**

- Exact same signatures as the browser
- No need to understand the WASM algorithm
- Automatically handles signature refresh via the event system

**Cons:**

- Requires loading the full hanime.tv page
- Heavier resource usage
- Dependent on page structure remaining stable

### 2. WASM Execution Approach

Extract and execute the WASM module directly in the app runtime:

```kotlin
// Extract the base64-encoded WASM binary from the vendor JS bundle
// Initialize a WASM runtime (e.g., wazero for JVM/Android)
// Replicate the JS glue: instantiate with ASM_CONSTS, call _main, emit "e" events
// Read window.ssignature and window.stime after each emission
```

**Pros:**

- No browser dependency
- Lightweight once initialized
- Produces identical signatures

**Cons:**

- Requires WASM runtime integration
- Need to replicate the JS↔WASM glue (ASM_CONSTS, event dispatch, ccall)
- WASM binary may change and need re-extraction from new vendor bundle

### 3. Server Proxy Approach

Run a small server that hosts the WASM and generates signatures on demand:

```python
# Server endpoint that returns fresh signatures
@app.get("/hanime/signature")
async def get_signature():
    # Run the WASM module server-side (e.g., via wasmtime or wasmer)
    # Emit "e" event, wait for window.stime to be set
    # Return { ssignature: "...", stime: "..." }
    pass
```

**Pros:**

- Centralized signature generation
- Easy to update if WASM changes
- All clients share one WASM instance

**Cons:**

- Requires server infrastructure
- Additional network latency
- Single point of failure

### Recommendation

For Aniyomi extensions, the **WebView approach** is most practical as it requires no
additional infrastructure and leverages the existing WebView component. The WASM execution
approach is the most elegant long-term solution but requires replicating the full JS glue
layer (ASM_CONSTS bridge, event dispatch, `Module.ccall` interface).

---

## Troubleshooting

### Common Issues

#### 1. "Invalid signature" / 401 Unauthorized Error

**Cause:** The signature was generated with the wrong algorithm (e.g., plain HMAC-SHA256)
or the signature has expired.

**Solution:**

- **Do NOT use plain HMAC-SHA256** — it does NOT produce valid signatures
- Ensure the signature comes from the WASM module (via WebView, WASM runtime, or proxy)
- Check that the signature hasn't expired — emit a fresh `"e"` event before each request
- If using cached signatures, implement a refresh mechanism

#### 2. WASM Initialization Fails

**Cause:** The JS glue code was not properly replicated, or ASM_CONSTS are missing.

**Solution:**

- Ensure `ASM_CONSTS[17392]` returns a valid Unix timestamp in seconds
- Ensure `ASM_CONSTS[17442]` correctly writes to `window.ssignature` and `window.stime`
- Call `initRuntime()` (export A) before `_main()` (export C)
- Do NOT call `initRuntime()` twice — it will throw `"Cannot register type 'void' twice"`

#### 3. "Expired signature" Error

**Cause:** The timestamp is too old (signature was generated too long ago).

**Solution:**

- Emit the `"e"` event to trigger a fresh signature before the API request
- Implement periodic signature refresh
- Ensure system clock is accurate

#### 4. WASM Function Errors ("memory access out of bounds")

**Cause:** WASM memory is fixed at 258 pages. `_emscripten_resize_heap` always aborts.

**Solution:**

- Do not attempt to resize WASM memory
- Ensure the WASM binary is loaded completely (the base64 string must not be truncated)
- Use the correct export mapping (see [WASM Export Table](#wasm-export-table))

#### 5. Polling Loop Never Resolves

**Cause:** `window.stime` is never set, meaning the WASM signature computation never
completes.

**Solution:**

- Verify that `_main()` was called to register the event listener
- Verify that `Emit("e")` is correctly dispatching a `CustomEvent`
- Check browser console for WASM instantiation errors
- Ensure the ASM_CONSTS bridge is functioning

### Debugging Tips

1. **Set a breakpoint on ASM_CONSTS[17442]** — this is where the signature is written.
   If it's never called, the WASM computation is failing silently.
2. **Log `window.ssignature` and `window.stime`** after emitting `"e"` to verify they
   are populated.
3. **Compare request headers** with working browser requests using DevTools Network tab.
4. **Monitor the polling loop** — if `window.stime` remains falsy after several seconds,
   the WASM module is not initializing correctly.
5. **Check the vendor bundle URL** — if the hash in the filename changes, the WASM
   binary may have been updated.

### Validation Checklist

Before submitting API requests:

- [ ] WASM module initialized (exports A, then C called)
- [ ] Event listener registered by `_main()`
- [ ] Fresh signature obtained by emitting `"e"` event
- [ ] `window.ssignature` is a 64-character lowercase hex string
- [ ] `window.stime` is a current Unix timestamp in seconds
- [ ] All required headers present (`x-signature`, `x-time`, `x-signature-version`, etc.)
- [ ] `x-signature-version` is `"web2"` (hardcoded)
- [ ] `content-type` is `"application/json"`
- [ ] Signature has not expired (generate fresh if uncertain)

---

## JS Bundle Distribution

All WASM and signature logic is concentrated in exactly **2 of the 7 JS bundles**. The other 5 bundles contain zero signature-related code.

### Active Bundles

| Bundle | Role | Key Patterns Found |
|--------|------|--------------------|
| `vendor.0130da3e01eaf5c7d570b6ed1becb5f4.min.js` | WASM module (loader, runtime, exports) | `AGFzb` (1), `WebAssembly.instantiate` (1), `wasmBinary` (11), `wasmExports` (15), `ssignature` (1), ASM_CONSTS, window_on, assignWasmExports, findWasmBinary |
| `40c99ce.js` | Vue/Nuxt app (HTTP headers, polling, init) | `ssignature` (2), `x-signature` (2), `x-time` (1), `x-signature-version` (1), `stime` (8), `window.ssignature` (2) |

### Inactive Bundles (no matches for any signature pattern)

| Bundle | Content |
|--------|---------|
| `ef036f2.js` | Unrelated UI/library code |
| `c1eb2c5.js` | Unrelated UI/library code (contains `$getApiBaseUrl`) |
| `a37eda4.js` | Unrelated UI/library code |
| `b28452f.js` | Unrelated UI/library code |

### Patterns NOT Found Anywhere

| Pattern | Status | Explanation |
|---------|--------|-------------|
| `0007b7b6.wasm` | Not found | No external .wasm file is referenced. Binary is inline base64 in vendor.js. |
| `sign.bin` | Not found | No signing key file is fetched. Key is embedded in the WASM binary. |
| `86200` / `86280` | Not found | Memory offsets are internal to WASM, not referenced in JS. |
| `.B(` | Not found | Export accessed as `wasmExports["B"]`, not `.B()` notation. |

---

## Next Steps for Algorithm Extraction

The WASM binary has been fully extracted and is available for analysis. To determine the
exact signature algorithm:

1. **Disassemble WAT function index 535** (export B = `_on_window_event`) — this is the
   signature computation entry point.

2. **Trace the call chain** from function 535 through its callees to the ASM_CONSTS calls.
   The path from event receipt → timestamp acquisition → signature computation → result
   writing reveals the algorithm.

3. **Look for hash-like operations** (SHA-256, MD5, custom) in the ~562 local functions.
   Common patterns include:
   - Compression functions (round-based bit operations on 32-bit words)
   - Merkle-Damgard constructions (padding, block processing, finalization)
   - Lookup tables (S-boxes, round constants)

4. **Use dynamic instrumentation** — set breakpoints on `ASM_CONSTS[17442]` and trace
   backwards through the WASM call stack to identify what data is fed into the final
   hash computation.

5. **Investigate the input** — determine whether the signature is computed from:
   - The timestamp alone
   - A combination of domain string + metadata + timestamp
   - Additional context passed via the event detail object
   - Some other data available in WASM memory at computation time

6. **Check the hex lookup table** at offset 4585 (`"0123456789ABCDEF"`) — this is used
   for the final hex encoding step. Tracing which function writes to the memory region
   that gets hex-encoded can reveal the pre-encoding binary hash output.

---

## File References

| File | Description |
|------|-------------|
| `docs/wasm_dump/0007b7b6.wasm` | WASM source in WAT format (1.55MB, text format) |
| `docs/wasm_dump/vendor.0130da3e01eaf5c7d570b6ed1becb5f4.min.js` | WASM loader with inline base64 binary |
| `docs/wasm_dump/40c99ce.js` | App bundle (header construction + polling loop) |

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 2.0 | 2026-04-21 | **Major rewrite.** Removed all references to `sign.bin` key fetch (key is embedded in WASM). Removed HMAC-SHA256 placeholders and examples. Corrected export table (B = `_on_window_event`, C = `_main`, D = `___getTypeName`, E = `_malloc`, F = `_free`, G/H/I = stack ops). Corrected memory layout (no hardcoded 86200 struct; stack at 0x14F50). Added WASM import table (24 imports). Added ASM_CONSTS bridge documentation. Added event-driven signature flow. Added HTTP header construction from 40c99ce.js. Added `Emit()` and polling loop documentation. Added data section findings. Added architectural summary. Added file references. Added algorithm extraction next steps. |
| 1.2 | 2026-04-20 | Critical corrections: algorithm is NOT HMAC-SHA256. Added WASM function table with verified behavior. Added memory struct layout. Added signature lifecycle. Added alternative implementation approaches. |
| 1.1 | 2026-04-19 | Added WASM-based signature generation discovery. |
| 1.0 | 2026-04-19 | Initial documentation based on reverse engineering findings. |

---

## Notes

This documentation is based on reverse engineering of the fully extracted WASM binary and
the associated JavaScript bundles. The WASM binary is self-contained — no external key
fetch is performed. The signing key is embedded within the WASM binary itself, likely in
the code section as numeric constants rather than as plaintext in the data section.

The exact signature algorithm remains unknown without full reverse engineering of WAT
function index 535 (`_on_window_event`) and its call chain. The presence of a hex lookup
table in the data section confirms that the final output step is hex-encoding of a binary
hash, but the hash algorithm and input construction are still to be determined.
