# hanime.tv Signature Generation Documentation

This document provides comprehensive documentation of the signature authentication system used by hanime.tv for API requests. This information is based on reverse engineering findings and covers the complete signature generation process.

> **⚠️ CRITICAL CORRECTION (v1.2):** Many original assumptions in this document were WRONG. The signature algorithm is **NOT plain HMAC-SHA256**. Verified browser experiments prove that no standard HMAC-SHA256 construction produces the correct signature. The actual algorithm is implemented inside a WASM binary and remains under investigation. All HMAC-SHA256 implementation examples below are **UNVERIFIED** and **will NOT produce valid signatures**.

---

## Table of Contents

1. [Overview](#overview)
2. [WASM-Based Signature Generation](#wasm-based-signature-generation)
3. [WASM Memory Struct Layout](#wasm-memory-struct-layout)
4. [Signature Lifecycle](#signature-lifecycle)
5. [The Key (Critical)](#the-key-critical)
6. [The Signature Format](#the-signature-format)
7. [Required Headers](#required-headers)
8. [Step-by-Step Generation Process](#step-by-step-generation-process)
9. [Implementation Examples](#implementation-examples)
10. [Alternative Implementation Approaches](#alternative-implementation-approaches)
11. [Troubleshooting](#troubleshooting)

---

## Overview

hanime.tv uses a **WASM-based custom signature algorithm** for API authentication. Every request to protected endpoints must include a valid signature in the `x-signature` header along with supporting authentication headers.

> **⚠️ IMPORTANT:** The original assumption that the algorithm is HMAC-SHA256 is **INCORRECT**. Extensive browser testing confirmed that NO standard HMAC-SHA256 variant matches the WASM output. The actual algorithm is implemented inside a compiled WebAssembly binary and is under investigation.

### Security Model

- **Algorithm**: **Under investigation — WASM-based custom algorithm** (NOT standard HMAC-SHA256)
- **Key Source**: Fetched dynamically from `https://hanime.tv/sign.bin`
- **Key Length**: 16 bytes (128 bits)
- **Signature Length**: 64 characters (hexadecimal)
- **Timestamp**: Unix timestamp in seconds
- **WASM Runtime**: Emscripten-compiled module with 9 exported functions

### What We Tested and Ruled Out

The following standard constructions were all tested and **NONE matched the WASM output**:

- `HMAC-SHA256(key="0123456701234567", msg=timestamp_string)` ❌
- `HMAC-SHA256(key=hex_decoded_0x0123456701234567, msg=timestamp_string)` ❌
- `HMAC-SHA256(key=SHA256(key), msg=timestamp)` ❌
- Double HMAC (HMAC of HMAC) ❌
- `SHA-256(key + timestamp)` or `SHA-256(timestamp + key)` ❌
- Any other standard construction tested ❌

The WASM binary must be extracted and disassembled to determine the actual algorithm.

---

## WASM-Based Signature Generation

**Important Discovery:** The signature is generated using WebAssembly (WASM), not plain JavaScript. This significantly increases the complexity of reverse engineering.

### WASM Module Access

The WASM module is exposed globally via `window.wasmExports`:

```javascript
// Access the WASM exports
const wasm = window.wasmExports;

// Available functions (VERIFIED):
// - z(): Returns WebAssembly.Memory (same as window.wasmMemory)
// - A(): Emscripten type registration/init
// - B(offset, length): Signature writer — writes 64-char hex to memory
// - C(offset, length): Memory copy function
// - D(): Unknown — crashes with wrong params
// - E(): Returns pointer to signature struct (86200)
// - F(): Setter/init function
// - G(): Setter/init function
// - H(): Returns 0
// - I(): Returns 0
```

### Browser Global Variables (Confirmed)

```javascript
window.ssignature    // "c1c40b42..." — 64-char hex signature, matches x-signature header
window.stime         // 1776643038 — Unix timestamp in seconds, matches x-time header
window.wasmExports   // { z, A, B, C, D, E, F, G, H, I } — WASM module exports
window.wasmMemory    // WebAssembly.Memory — shared WASM linear memory (same as wasmExports.z)
```

### WASM Function Signatures (VERIFIED)

Based on direct browser experimentation, the exports behave as follows:

| Function | Params | Return | Verified Purpose |
|----------|--------|--------|-----------------|
| `z` | N/A | WebAssembly.Memory | The WASM linear memory (same as window.wasmMemory) |
| `A` | 0 | Error: "Cannot register type 'void' twice" | Emscripten type registration/init — crashes if called twice |
| `B` | 2 (offset, length) | undefined (void) | Writes 64-char hex signature to offset 86280 in WASM memory. Reads timestamp from internal struct at offset 86216, NOT from the input parameters. |
| `C` | 2 (offset, length) | number (pointer) | Memory copy function — returns pointer to copied data |
| `D` | unknown | Error: "memory access out of bounds" | Needs specific params, crashes with wrong input |
| `E` | 0 | number (86200) | Returns pointer to the signature struct at offset 86200 |
| `F` | unknown | undefined (void) | Setter/init function |
| `G` | unknown | undefined (void) | Setter/init function |
| `H` | 0 | number (0) | Returns 0, purpose unknown |
| `I` | 0 | number (0) | Returns 0, purpose unknown |

### B() Function Behavior (CRITICAL)

- `B(offset, length)` does **NOT** use the offset/length to read the timestamp from arbitrary memory
- `B()` reads the timestamp from the **fixed struct at offset 86216**
- `B()` writes the 64-char hex signature to **offset 86280**
- The offset/length parameters appear to control WHERE B() reads its input within the struct (not from arbitrary memory)
- Calling `B()` with incorrect offsets causes "memory access out of bounds" crashes
- The signature is also found at offset 86056 (in the WASM data segment near other string constants like `"US.UTF-8"` and `"this.program"`)

### Memory Access Pattern

WASM uses `window.wasmMemory` for data exchange between JavaScript and WASM:

```javascript
// Read from WASM memory
const memory = new Uint8Array(window.wasmMemory.buffer);
const result = memory.slice(offset, offset + length);

// Write to WASM memory (e.g., write timestamp before calling B())
memory.set(data, offset);
```

### Why WASM?

hanime.tv uses WASM for signature generation because it:

- Makes reverse engineering significantly more difficult
- Protects the algorithm from easy inspection
- Allows use of cryptographic primitives not visible in JavaScript
- Requires either:
  - Running the actual WASM module
  - Extracting and analyzing the WASM binary
  - Reimplementing the algorithm

The WASM appears to be compiled with **Emscripten** (based on `A()` error message about type registration).

### Current Research Status

- ✅ `B()` is **confirmed** as the signature writer function
- ✅ `E()` is **confirmed** as returning the pointer to the signature struct at offset 86200
- ✅ The algorithm is **definitively NOT** standard HMAC-SHA256
- ❌ The actual algorithm inside the WASM binary is still unknown
- ❌ The WASM binary has not yet been extracted for disassembly
- **Next steps for reverse engineering:**
  1. Extract the WASM binary (likely inline/base64-encoded in one of the JS bundles at `hanime-cdn.com/vhtv2/*.js`)
  2. Disassemble using `wasm2wat` or similar tool
  3. Analyze function B (wasm-function[535]) and its callees

**Note:** For reverse engineering assistance, see [WASM Reverse Engineering Guide](./wasm_reverse_engineer.md).

---

## WASM Memory Struct Layout

**Critical Discovery:** At offset 86200 (returned by `E()`), there is a structured data region used by the signature generation process.

### Struct Layout Table

| Offset | Content | Description |
|--------|---------|-------------|
| 86200 | `"//hanime.tv\0"` | Domain string (13 bytes + null) |
| 86212 | `[0x19, 0x0F, 0x00, 0x00]` | Metadata/length field (little-endian) |
| 86216 | `"1776647020\0"` | Current Unix timestamp string (10 bytes + null) |
| 86226–86275 | zeros | Padding/reserved |
| 86276 | `[0xD9, 0x0E, 0x00, 0x00]` | Metadata/length prefix |
| 86280 | `<64-char hex signature>` | The generated signature output |

### Key Insight

`B()` reads the timestamp from offset **86216** in this struct, NOT from its input parameters. The JavaScript code must write the timestamp to this struct **before** `B()` is called. The flow is:

1. JS writes current Unix timestamp string to offset 86216
2. JS calls `B(offset, length)`
3. `B()` reads timestamp from offset 86216 in the struct
4. `B()` writes the 64-char hex signature to offset 86280
5. JS reads the signature from offset 86280

### Reading the Signature from WASM Memory

```javascript
// After B() has been called:
const memory = new Uint8Array(window.wasmMemory.buffer);

// Read the 64-char hex signature from offset 86280
const signatureBytes = memory.slice(86280, 86280 + 64);
const signature = new TextDecoder().decode(signatureBytes);

// Read the timestamp from offset 86216
const timestampBytes = memory.slice(86216, 86216 + 10);
const timestamp = new TextDecoder().decode(timestampBytes);
```

---

## Signature Lifecycle

### Periodic Generation (NOT On-Demand)

The signature is generated **periodically by a timer**, NOT on-demand per API request:

- `window.ssignature` is a 64-char hex string that matches the WASM output exactly
- `window.stime` is the Unix timestamp in seconds
- API requests simply **read** `window.ssignature` and `window.stime` at request time
- The signature refresh timer runs in the background (exact interval not determined, but at least several seconds)

### Signature Expiry

- Signatures **EXPIRE** — we observed **401 Unauthorized** responses on the playlists endpoint when using an old signature
- The exact validity window is not yet determined
- Implementations must refresh signatures periodically, not just generate once

### Implications for Implementations

- Do NOT assume a signature is valid indefinitely
- Implement a timer-based refresh mechanism (similar to the browser's approach)
- If a 401 is received, refresh the signature and retry

---

## The Key (Critical)

The signing key is **not static** and must be retrieved from hanime.tv's servers before generating signatures.

> **⚠️ Note:** While the key IS confirmed (see below), the algorithm that uses it is **NOT plain HMAC-SHA256**. The key is consumed by the WASM binary, and the WASM's internal algorithm is unknown.

### Key Retrieval

**Endpoint:**
```
GET https://hanime.tv/sign.bin
```

**Response Details:**

| Property | Value |
|----------|-------|
| HTTP Method | `GET` |
| Content-Type | `application/octet-stream` |
| Response Size | 16 bytes |
| Response Encoding | Binary |

**Response Content:**

The response contains exactly 16 bytes of binary data. When interpreted as ASCII, the key is:

```
0123456701234567
```

**Key Properties:**

| Property | Value |
|----------|-------|
| Length | 16 bytes |
| Bit Length | 128 bits |
| ASCII Content | `0123456701234567` |
| Binary Content | `0x30 0x31 0x32 0x33 0x34 0x35 0x36 0x37 0x30 0x31 0x32 0x33 0x34 0x35 0x36 0x37` |
| Hex Representation | `30313233343536373031323334353637` |
| Algorithm | **WASM-based custom algorithm** (NOT HMAC-SHA256) |

### Key Usage

The key is used by the WASM signature algorithm. The same key is also used for **AES-128 encryption** of video segments in the HLS streams. The key appears to be **static/unchanging**.

---

## The Signature Format

### Signature Characteristics

| Property | Value |
|----------|-------|
| Format | Hexadecimal string |
| Length | 64 characters |
| Bit Length | 256 bits |
| Algorithm | **WASM-based custom algorithm** (NOT HMAC-SHA256) |
| Case | Lowercase |

### Example Signatures

```
99ce0e4c35fea0b69bae9e177c614d224225897adb2b203246e2170ff1f509c5
8567328e24f9cbc47052b338a21f2cb056275388668be224262df46e12a68084
c1c40b42...
```

### Browser-Based Access

The signature is pre-computed and available in the browser's global scope:

```javascript
// Access signature from global JavaScript
window.ssignature // "c1c40b42..." — 64-char hex signature, matches x-signature header
window.stime      // 1776643038 (Unix timestamp)
```

---

## Required Headers

Every API request requires the following authentication headers:

### Header Specification

| Header | Type | Guest Value | Description |
|--------|------|-------------|-------------|
| `x-signature` | string | **Required** | 64-char hex signature from WASM |
| `x-time` | integer | **Required** | Unix timestamp in seconds |
| `x-signature-version` | string | `web2` | Signature algorithm version |
| `x-session-token` | string | `""` (empty) | Session authentication token |
| `x-user-license` | string | `""` (empty) | User license tier |
| `x-csrf-token` | string | `""` (empty) | CSRF protection token |
| `x-license` | string | `""` (empty) | License verification |

### Complete Header Example (VERIFIED from live traffic)

**Guest Request Headers:**
```http
x-signature: <64-char hex from window.ssignature>
x-time: <Unix timestamp from window.stime>
x-signature-version: web2
x-session-token:
x-user-license:
x-csrf-token:
x-license:
```

### Authenticated Request Headers

For authenticated requests, populate the token fields:
```http
x-signature: <signature_from_wasm>
x-time: <timestamp>
x-signature-version: web2
x-session-token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
x-user-license: premium
x-csrf-token: <csrf_token>
x-license: <license_string>
```

---

## Step-by-Step Generation Process

> **⚠️ WARNING:** Steps 3 and 4 below describe a plain HMAC-SHA256 process that has been **proven INCORRECT**. The actual algorithm is unknown and implemented in WASM. These steps are preserved as a reference for what was originally assumed, but they **will NOT produce valid signatures**.

### Step 1: Retrieve the Signing Key

Fetch the 16-byte key from the sign.bin endpoint:

```python
import requests

# Fetch the key
response = requests.get('https://hanime.tv/sign.bin')
key = response.content  # 16 bytes: b'0123456701234567'

# Verify key length
assert len(key) == 16, f"Expected 16 bytes, got {len(key)}"
```

### Step 2: Generate Timestamp

Create a Unix timestamp in seconds:

```python
import time

timestamp = str(int(time.time()))  # e.g., "1776647020"
```

**Important:** The timestamp must be in **seconds**, not milliseconds.

### Step 3: Construct the Message ⚠️ UNVERIFIED

> **⚠️ INCORRECT — The message format described below is based on the HMAC-SHA256 assumption and does NOT produce correct signatures.**

The assumed message format for the HMAC was:

```
message = timestamp
```

**Actual behavior:** The WASM reads the timestamp from its internal memory struct at offset 86216. The JS code must write the timestamp string to that offset before calling `B()`.

### Step 4: Generate the Signature ⚠️ UNVERIFIED

> **⚠️ INCORRECT — The HMAC-SHA256 generation described below does NOT produce correct signatures. The actual algorithm is implemented inside the WASM binary and is unknown.**

The assumed HMAC generation was:

```python
import hmac
import hashlib

# THIS DOES NOT WORK — signature will be invalid
signature = hmac.new(
    key,  # 16-byte key from sign.bin
    timestamp.encode(),  # message to sign
    hashlib.sha256  # hash algorithm
).hexdigest()
```

**Actual behavior:** The JS code calls `B(offset, length)` on the WASM module, which reads the timestamp from the struct at offset 86216, applies an unknown algorithm, and writes the 64-char hex signature to offset 86280.

### Step 5: Build Request Headers

Construct the complete header set:

```python
headers = {
    'x-signature': signature,  # 64-char hex from WASM
    'x-time': timestamp,
    'x-signature-version': 'web2',
    'x-session-token': '',
    'x-user-license': '',
    'x-csrf-token': '',
    'x-license': '',
    'Referer': 'https://hanime.tv/',
    'Accept': 'application/json'
}
```

### Step 6: Make the API Request

Use the headers to authenticate your request:

```python
response = requests.get(
    'https://cached.freeanimehentai.net/api/v8/guest/videos/3427/manifest',
    headers=headers
)
```

---

## Implementation Examples

> **⚠️ CRITICAL WARNING:** All implementation examples below use plain HMAC-SHA256, which has been **proven to NOT produce correct signatures**. These are included as **PLACEHOLDER examples only** — they **will not produce valid signatures** and API requests made with them will receive 401 Unauthorized responses.
>
> For working approaches, see [Alternative Implementation Approaches](#alternative-implementation-approaches).

### Python Implementation ⚠️ PLACEHOLDER — Will Not Produce Valid Signatures

```python
"""
hanime.tv API Signature Generator
⚠️ PLACEHOLDER — This implementation uses HMAC-SHA256 which does NOT produce
valid signatures. The actual algorithm is WASM-based and unknown.
"""
import hmac
import hashlib
import requests
import time
from typing import Dict


class HanimeSignatureGenerator:
    """⚠️ PLACEHOLDER — Generates HMAC-SHA256 signatures that are NOT valid."""

    SIGN_KEY_URL = 'https://hanime.tv/sign.bin'
    API_BASE = 'https://cached.freeanimehentai.net'

    def __init__(self):
        self._key: bytes = None

    def fetch_key(self) -> bytes:
        """Fetch the 16-byte key from sign.bin."""
        response = requests.get(self.SIGN_KEY_URL)
        response.raise_for_status()

        key = response.content
        if len(key) != 16:
            raise ValueError(f"Expected 16-byte key, got {len(key)} bytes")

        self._key = key
        return key

    def generate_signature(self, timestamp: str = None) -> str:
        """
        ⚠️ PLACEHOLDER — This HMAC-SHA256 approach does NOT work.
        The actual signature is generated by a WASM binary.
        """
        if self._key is None:
            self.fetch_key()

        if timestamp is None:
            timestamp = str(int(time.time()))

        # ⚠️ THIS DOES NOT PRODUCE VALID SIGNATURES
        signature = hmac.new(
            self._key,
            timestamp.encode('utf-8'),
            hashlib.sha256
        ).hexdigest()

        return signature

    def build_headers(self, timestamp: str = None) -> Dict[str, str]:
        """
        ⚠️ PLACEHOLDER — Headers built with this will be rejected.
        """
        if timestamp is None:
            timestamp = str(int(time.time()))

        signature = self.generate_signature(timestamp)

        return {
            'x-signature': signature,
            'x-time': timestamp,
            'x-signature-version': 'web2',
            'x-session-token': '',
            'x-user-license': '',
            'x-csrf-token': '',
            'x-license': '',
            'Referer': 'https://hanime.tv/',
            'Accept': 'application/json'
        }

    def make_request(self, endpoint: str) -> dict:
        """
        ⚠️ PLACEHOLDER — Requests will fail with 401 Unauthorized.
        """
        headers = self.build_headers()
        url = f"{self.API_BASE}{endpoint}"

        response = requests.get(url, headers=headers)
        response.raise_for_status()

        return response.json()


# Example usage (will NOT work — signatures are invalid)
if __name__ == "__main__":
    generator = HanimeSignatureGenerator()
    signature = generator.generate_signature()
    print(f"Signature: {signature}  # ⚠️ This signature is INVALID")
```

### JavaScript/Node.js Implementation ⚠️ PLACEHOLDER — Will Not Produce Valid Signatures

```javascript
/**
 * hanime.tv API Signature Generator
 * ⚠️ PLACEHOLDER — This implementation uses HMAC-SHA256 which does NOT produce
 * valid signatures. The actual algorithm is WASM-based and unknown.
 */
const crypto = require('crypto');
const axios = require('axios');

class HanimeSignatureGenerator {
  constructor() {
    this.key = null;
    this.apiBase = 'https://cached.freeanimehentai.net';
    this.signKeyUrl = 'https://hanime.tv/sign.bin';
  }

  /**
   * Fetch the 16-byte key from sign.bin
   * @returns {Promise<Buffer>} 16-byte key
   */
  async fetchKey() {
    const response = await axios.get(this.signKeyUrl, {
      responseType: 'arraybuffer'
    });

    this.key = Buffer.from(response.data);

    if (this.key.length !== 16) {
      throw new Error(`Expected 16-byte key, got ${this.key.length} bytes`);
    }

    return this.key;
  }

  /**
   * ⚠️ PLACEHOLDER — This HMAC-SHA256 approach does NOT work.
   * The actual signature is generated by a WASM binary.
   * @param {string} timestamp - Unix timestamp in seconds
   * @returns {Promise<string>} 64-character hex signature (INVALID)
   */
  async generateSignature(timestamp = null) {
    if (!this.key) {
      await this.fetchKey();
    }

    const ts = timestamp || Math.floor(Date.now() / 1000).toString();

    // ⚠️ THIS DOES NOT PRODUCE VALID SIGNATURES
    const signature = crypto
      .createHmac('sha256', this.key)
      .update(ts)
      .digest('hex');

    return signature;
  }

  /**
   * ⚠️ PLACEHOLDER — Headers built with this will be rejected.
   * @param {string} timestamp - Unix timestamp
   * @returns {Promise<Object>} Headers object
   */
  async buildHeaders(timestamp = null) {
    const ts = timestamp || Math.floor(Date.now() / 1000).toString();
    const signature = await this.generateSignature(ts);

    return {
      'x-signature': signature,
      'x-time': ts,
      'x-signature-version': 'web2',
      'x-session-token': '',
      'x-user-license': '',
      'x-csrf-token': '',
      'x-license': '',
      'Referer': 'https://hanime.tv/',
      'Accept': 'application/json'
    };
  }

  /**
   * ⚠️ PLACEHOLDER — Requests will fail with 401 Unauthorized.
   * @param {string} endpoint - API endpoint path
   * @returns {Promise<Object>} JSON response
   */
  async makeRequest(endpoint) {
    const headers = await this.buildHeaders();
    const url = `${this.apiBase}${endpoint}`;

    const response = await axios.get(url, { headers });
    return response.data;
  }
}

// Example usage (will NOT work — signatures are invalid)
async function main() {
  const generator = new HanimeSignatureGenerator();

  try {
    const signature = await generator.generateSignature();
    console.log('Signature:', signature, '  # ⚠️ This signature is INVALID');
  } catch (error) {
    console.error('Error:', error.message);
  }
}

main();
```

### Kotlin Implementation ⚠️ PLACEHOLDER — Will Not Produce Valid Signatures

```kotlin
/**
 * hanime.tv API Signature Generator
 * ⚠️ PLACEHOLDER — This implementation uses HMAC-SHA256 which does NOT produce
 * valid signatures. The actual algorithm is WASM-based and unknown.
 */
package eu.kanade.tachiyomi.extension.all.hanime.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * ⚠️ PLACEHOLDER — Generates HMAC-SHA256 signatures that are NOT valid.
 *
 * The actual signature is generated by a WASM binary using an unknown algorithm.
 * The 16-byte key from sign.bin IS confirmed, but the algorithm is NOT HMAC-SHA256.
 */
class HanimeSignatureGenerator(
    private val client: OkHttpClient
) {
    companion object {
        const val SIGN_KEY_URL = "https://hanime.tv/sign.bin"
        const val API_BASE = "https://cached.freeanimehentai.net"
    }

    private var cachedKey: ByteArray? = null

    /**
     * Fetch the 16-byte key from sign.bin.
     * The key is cached for subsequent requests.
     *
     * @return 16-byte key
     */
    suspend fun fetchKey(): ByteArray {
        cachedKey?.let { return it }

        val request = Request.Builder()
            .url(SIGN_KEY_URL)
            .build()

        return suspendCoroutine { continuation ->
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    continuation.resumeWithException(
                        IllegalStateException("Failed to fetch key: ${response.code}")
                    )
                    return@use
                }

                val key = response.body?.bytes()
                    ?: run {
                        continuation.resumeWithException(
                            IllegalStateException("Empty key response")
                        )
                        return@use
                    }

                if (key.size != 16) {
                    continuation.resumeWithException(
                        IllegalArgumentException("Expected 16-byte key, got ${key.size}")
                    )
                    return@use
                }

                cachedKey = key
                continuation.resume(key)
            }
        }
    }

    /**
     * ⚠️ PLACEHOLDER — This HMAC-SHA256 approach does NOT work.
     * The actual signature is generated by a WASM binary.
     *
     * @param key 16-byte key
     * @param timestamp Unix timestamp in seconds
     * @return 64-character hex-encoded signature (INVALID)
     */
    fun generateSignature(key: ByteArray, timestamp: String): String {
        require(key.size == 16) { "Key must be 16 bytes" }

        // ⚠️ THIS DOES NOT PRODUCE VALID SIGNATURES
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))

        val signatureBytes = mac.doFinal(timestamp.toByteArray())

        // Convert to hex string
        return signatureBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * ⚠️ PLACEHOLDER — Headers built with this will be rejected.
     *
     * @param timestamp Unix timestamp (auto-generated if null)
     * @param sessionToken Optional session token for authenticated requests
     * @return Map of HTTP headers
     */
    suspend fun buildHeaders(
        timestamp: String? = null,
        sessionToken: String = "",
        userLicense: String = "",
        csrfToken: String = "",
        license: String = ""
    ): Map<String, String> {
        val key = fetchKey()
        val ts = timestamp ?: (System.currentTimeMillis() / 1000).toString()
        val signature = generateSignature(key, ts)

        return mapOf(
            "x-signature" to signature,
            "x-time" to ts,
            "x-signature-version" to "web2",
            "x-session-token" to sessionToken,
            "x-user-license" to userLicense,
            "x-csrf-token" to csrfToken,
            "x-license" to license,
            "Referer" to "https://hanime.tv/",
            "Accept" to "application/json"
        )
    }
}

// ⚠️ Example usage will NOT work — signatures are invalid
class HanimeApi(private val client: OkHttpClient) {
    private val signatureGenerator = HanimeSignatureGenerator(client)

    suspend fun fetchVideoManifest(hvId: Int): ManifestResponse {
        val headers = signatureGenerator.buildHeaders()

        val request = Request.Builder()
            .url("$API_BASE/api/v8/guest/videos/$hvId/manifest")
            .apply {
                headers.forEach { (key, value) -> addHeader(key, value) }
            }
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("API request failed: ${response.code}")
            }

            val body = response.body?.string()
                ?: throw IOException("Empty response")

            Json.decodeFromString(ManifestResponse.serializer(), body)
        }
    }
}
```

### cURL Example ⚠️ PLACEHOLDER — Will Not Produce Valid Signatures

```bash
#!/bin/bash

# ⚠️ PLACEHOLDER — This script generates HMAC-SHA256 signatures that are NOT valid.
# The actual algorithm is WASM-based and cannot be reproduced with standard tools.

# Fetch the key
KEY=$(curl -s https://hanime.tv/sign.bin | xxd -p | tr -d '\n' | head -c 32)
echo "Key (hex): $KEY"

# Generate timestamp
TIMESTAMP=$(date +%s)
echo "Timestamp: $TIMESTAMP"

# ⚠️ THIS HMAC-SHA256 SIGNATURE IS INVALID
SIGNATURE=$(echo -n "$TIMESTAMP" | openssl dgst -sha256 -hmac "$KEY" -binary | xxd -p | tr -d '\n')
echo "Signature: $SIGNATURE  # ⚠️ This signature is INVALID"

# ⚠️ This request will fail with 401 Unauthorized
curl -X GET \
  "https://cached.freeanimehentai.net/api/v8/guest/videos/3427/manifest" \
  -H "x-signature: $SIGNATURE" \
  -H "x-time: $TIMESTAMP" \
  -H "x-signature-version: web2" \
  -H "x-session-token: " \
  -H "x-user-license: " \
  -H "x-csrf-token: " \
  -H "x-license: " \
  -H "Referer: https://hanime.tv/" \
  -H "Accept: application/json"
```

---

## Alternative Implementation Approaches

Until the WASM algorithm is fully reverse-engineered, the following approaches can work:

### 1. WebView Approach

Load hanime.tv in a WebView and read `window.ssignature` and `window.stime` directly:

```kotlin
// Android/Aniyomi WebView approach
webView.evaluateJavascript("window.ssignature") { signature ->
    webView.evaluateJavascript("window.stime") { time ->
        // Use signature and time for API calls
        val headers = mapOf(
            "x-signature" to signature.trimQuotes(),
            "x-time" to time.trimQuotes(),
            "x-signature-version" to "web2",
            // ... other headers
        )
    }
}
```

**Pros:**
- Exact same signatures as the browser
- No need to understand the WASM algorithm
- Automatically handles signature refresh

**Cons:**
- Requires loading the full hanime.tv page
- Heavier resource usage
- Dependent on page structure remaining stable

### 2. WASM Execution Approach

Download and execute the WASM module directly in the app runtime:

```kotlin
// Fetch the WASM binary from the JS bundle
// Initialize a WASM runtime (e.g., wazero for JVM/Android)
// Call B(offset, length) after setting up the memory struct
// Read the signature from offset 86280
```

**Pros:**
- No browser dependency
- Lightweight once initialized
- Produces identical signatures

**Cons:**
- Requires WASM runtime integration
- Need to replicate the JS↔WASM memory setup
- WASM binary may change and need re-extraction

### 3. Server Proxy Approach

Run a small server that hosts the WASM and generates signatures on demand:

```python
# Server endpoint that returns fresh signatures
@app.get("/hanime/signature")
async def get_signature():
    # Run the WASM module server-side
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

For Aniyomi extensions, the **WebView approach** is most practical as it requires no additional infrastructure and leverages the existing WebView component. The WASM execution approach is the most elegant long-term solution but requires more engineering effort.

---

## Troubleshooting

### Common Issues

#### 1. "Invalid signature" / 401 Unauthorized Error

**Cause:** The signature was generated with the wrong algorithm (e.g., plain HMAC-SHA256) or the signature has expired.

**Solution:**
- ⚠️ **Do NOT use plain HMAC-SHA256** — it does NOT produce valid signatures
- Ensure the signature comes from the WASM module (via WebView, WASM runtime, or proxy)
- Check that the signature hasn't expired — signatures have a limited validity window
- If using cached signatures, implement a refresh mechanism

#### 2. "Key length mismatch"

**Cause:** The sign.bin endpoint returned unexpected data.

**Solution:**
- Verify the request to sign.bin succeeded
- Check response is exactly 16 bytes
- The endpoint may have changed - verify URL
- The key itself IS confirmed as `"0123456701234567"`, but it's consumed by the WASM, not HMAC

#### 3. "Expired signature" Error

**Cause:** The timestamp is too old (signature was generated too long ago).

**Solution:**
- Use a freshly generated signature from the WASM module
- Implement periodic signature refresh (the browser uses a background timer)
- Signatures have been observed to expire within a limited window
- Ensure system clock is accurate

#### 4. WASM Function Errors ("memory access out of bounds", "Cannot register type 'void' twice")

**Cause:** WASM functions were called with incorrect parameters or in the wrong order.

**Solution:**
- `A()` crashes if called twice — only call once for initialization
- `B()` requires specific offset/length values — use the struct at offset 86200
- `D()` requires specific params that are not yet known — avoid calling directly
- Always write the timestamp to offset 86216 before calling `B()`

#### 5. Network Errors When Fetching Key

**Cause:** Connection issues or endpoint unavailable.

**Solution:**
- Implement retry logic with exponential backoff
- Cache the key locally (it appears to be static)
- Check for proxy or firewall issues

#### 6. CORS Errors in Browser

**Cause:** Direct API calls from browser may trigger CORS.

**Solution:**
- Use a backend proxy
- Extract signature from page's JavaScript instead
- Access `window.ssignature` and `window.stime` directly

### Debugging Tips

1. **Log all request headers** to compare with working browser requests
2. **Verify key content** by printing hex representation
3. **Check timestamp synchronization** between client and server
4. **Compare signature generation** — if your output differs from `window.ssignature`, your algorithm is wrong
5. **Test signature validity** by making a request immediately after generation
6. **Monitor `window.ssignature`** over time to observe refresh behavior

### Validation Checklist

Before submitting API requests:

- [ ] Key fetched successfully (16 bytes: `"0123456701234567"`)
- [ ] Timestamp is current Unix time in seconds
- [ ] Signature generated by WASM module (NOT plain HMAC-SHA256)
- [ ] Signature is 64-character lowercase hex string
- [ ] Signature matches `window.ssignature` if available for comparison
- [ ] All required headers present
- [ ] Header values are properly formatted
- [ ] Referer header is set to hanime.tv
- [ ] Signature has not expired (generate fresh if uncertain)

---

## Additional Resources

- [API.md](./API.md) - Complete API documentation
- [HLS.md](./HLS.md) - HLS streaming documentation (if available)
- [WASM Reverse Engineering Guide](./wasm_reverse_engineer.md) - Guide for analyzing the WASM binary

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.2 | 2026-04-20 | **CRITICAL CORRECTIONS**: Algorithm is NOT HMAC-SHA256 (verified by browser experiments). Corrected WASM function table with verified behavior. Added WASM memory struct layout (offset 86200). Added signature lifecycle (periodic timer, expiry). Added alternative implementation approaches. Marked all HMAC examples as invalid placeholders. Added browser global variables confirmation. |
| 1.1 | 2026-04-19 | Added WASM-based signature generation discovery |
| 1.0 | 2026-04-19 | Initial documentation based on reverse engineering findings |

---

## Notes

This documentation is based on reverse engineering efforts and may change as the hanime.tv API evolves. The sign.bin endpoint and signature format should be monitored for changes.

**Key Discovery:** The same key used for API authentication (`0123456701234567`) is also used for AES-128 encryption of video segments in HLS streams.

**Algorithm Status:** The signature algorithm is definitively NOT plain HMAC-SHA256. All standard HMAC/SHA-256 constructions were tested and none matched the WASM output. The WASM binary must be extracted (likely from `hanime-cdn.com/vhtv2/*.js` bundles) and disassembled (using `wasm2wat`) to determine the actual algorithm. Function B (wasm-function[535]) is the signature writer, and its callees must be analyzed.
