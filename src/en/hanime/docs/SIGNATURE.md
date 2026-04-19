# hanime.tv Signature Generation Documentation

This document provides comprehensive documentation of the signature authentication system used by hanime.tv for API requests. This information is based on reverse engineering findings and covers the complete signature generation process.

---

## Table of Contents

1. [Overview](#overview)
2. [WASM-Based Signature Generation](#wasm-based-signature-generation)
3. [The Key (Critical)](#the-key-critical)
4. [The Signature Format](#the-signature-format)
5. [Required Headers](#required-headers)
6. [Step-by-Step Generation Process](#step-by-step-generation-process)
7. [Implementation Examples](#implementation-examples)
8. [Troubleshooting](#troubleshooting)

---

## Overview

hanime.tv uses HMAC-SHA256 signatures for API authentication. Every request to protected endpoints must include a valid signature in the `x-signature` header along with supporting authentication headers.

### Security Model

- **Algorithm**: HMAC-SHA256
- **Key Source**: Fetched dynamically from `https://hanime.tv/sign.bin`
- **Key Length**: 16 bytes (128 bits)
- **Signature Length**: 64 characters (hexadecimal)
- **Timestamp**: Unix timestamp in seconds

---

## WASM-Based Signature Generation

**Important Discovery:** The signature is generated using WebAssembly (WASM), not plain JavaScript. This significantly increases the complexity of reverse engineering.

### WASM Module Access

The WASM module is exposed globally via `window.wasmExports`:

```javascript
// Access the WASM exports
const wasm = window.wasmExports;

// Available functions:
// - z: Object (state holder)
// - A, B, C, D, E, F, G, H, I: Functions
```

### WASM Function Signatures

Based on reverse engineering analysis, the exports include:

| Function | Parameters | Likely Purpose |
|----------|------------|----------------|
| `A` | 0 | Initialization or state reset |
| `B` | 2 (data, length) | **Prime candidate: signature generation** |
| `C` | 2 (data, length) | **Prime candidate: signature generation** |
| `D` | 1 (data) | Utility function |
| `E` | 1 (data) | Utility function |
| `F` | 1 (data) | Utility function |
| `G` | 1 (data) | Utility function |
| `H` | 1 (data) | Utility function |
| `I` | 0 | Finalization or cleanup |
| `z` | Object | State/data holder |

### Memory Access Pattern

WASM uses `window.wasmMemory` for data exchange between JavaScript and WASM:

```javascript
// Read from WASM memory
const memory = new Uint8Array(window.wasmMemory.buffer);
const result = memory.slice(offset, offset + length);

// Write to WASM memory
memory.set(data, offset);
```

### Signature Flow Analysis

The signature generation process likely follows this pattern:

1. **Input preparation**: Convert timestamp to bytes
2. **Memory writing**: Write input data to WASM memory buffer
3. **Function invocation**: Call a WASM export (likely B or C)
4. **Memory reading**: Read result from memory buffer
5. **Output conversion**: Convert bytes to 64-character hex string

### Why WASM?

hanime.tv uses WASM for signature generation because it:

- Makes reverse engineering significantly more difficult
- Protects the algorithm from easy inspection
- Allows use of cryptographic primitives not visible in JavaScript
- Requires either:
  - Running the actual WASM module
  - Extracting and analyzing the WASM binary
  - Reimplementing the algorithm

### Current Research Status

- The WASM module and exports are identified
- Functions B and C (2 parameters) are prime candidates for signature generation
- The exact function and message format are still being determined
- Use the included `wasm_reverse_engineer.js` script to assist with identification

**Note:** For reverse engineering assistance, see [WASM Reverse Engineering Guide](./wasm_reverse_engineer.md).

---

## The Key (Critical)

The signing key is **not static** and must be retrieved from hanime.tv's servers before generating signatures.

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
| Algorithm | HMAC-SHA256 |

### Key Usage

The key is used as the HMAC secret for generating SHA256-based message authentication codes. The same key is also used for AES-128 encryption of video segments in the HLS streams.

---

## The Signature Format

### Signature Characteristics

| Property | Value |
|----------|-------|
| Format | Hexadecimal string |
| Length | 64 characters |
| Bit Length | 256 bits |
| Algorithm | HMAC-SHA256 |
| Case | Lowercase |

### Example Signatures

```
99ce0e4c35fea0b69bae9e177c614d224225897adb2b203246e2170ff1f509c5
8567328e24f9cbc47052b338a21f2cb056275388668be224262df46e12a68084
```

### Browser-Based Access

The signature is pre-computed and available in the browser's global scope:

```javascript
// Access signature from global JavaScript
window.ssignature  // "99ce0e4c35fea0b69bae9e177c614d224225897adb2b203246e2170ff1f509c5"
window.stime       // 1776569239 (Unix timestamp)
```

---

## Required Headers

Every API request requires the following authentication headers:

### Header Specification

| Header | Type | Guest Value | Description |
|--------|------|-------------|-------------|
| `x-signature` | string | **Required** | 64-char HMAC-SHA256 hex signature |
| `x-time` | integer | **Required** | Unix timestamp in seconds |
| `x-signature-version` | string | `web2` | Signature algorithm version |
| `x-session-token` | string | `""` (empty) | Session authentication token |
| `x-user-license` | string | `""` (empty) | User license tier |
| `x-csrf-token` | string | `""` (empty) | CSRF protection token |
| `x-license` | string | `""` (empty) | License verification |

### Complete Header Example

**Guest Request Headers:**
```http
x-signature: 99ce0e4c35fea0b69bae9e177c614d224225897adb2b203246e2170ff1f509c5
x-time: 1776569239
x-signature-version: web2
x-session-token: 
x-user-license: 
x-csrf-token: 
x-license: 
```

### Authenticated Request Headers

For authenticated requests, populate the token fields:
```http
x-signature: <hmac_signature>
x-time: <timestamp>
x-signature-version: web2
x-session-token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
x-user-license: premium
x-csrf-token: <csrf_token>
x-license: <license_string>
```

---

## Step-by-Step Generation Process

### Step 1: Retrieve the HMAC Key

Fetch the 16-byte HMAC key from the sign.bin endpoint:

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

timestamp = str(int(time.time()))  # e.g., "1776569239"
```

**Important:** The timestamp must be in **seconds**, not milliseconds.

### Step 3: Construct the Message

The message format for the HMAC is:

```
message = timestamp
```

Note: The exact message format is still being verified. The timestamp alone appears to be the primary component, but additional request data may be included.

### Step 4: Generate HMAC-SHA256 Signature

Use the key to generate the HMAC:

```python
import hmac
import hashlib

# Create HMAC using SHA256
signature = hmac.new(
    key,                    # 16-byte key from sign.bin
    timestamp.encode(),     # message to sign
    hashlib.sha256          # hash algorithm
).hexdigest()               # get hex representation

# Result: 64-character hex string
```

### Step 5: Build Request Headers

Construct the complete header set:

```python
headers = {
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

### Python Implementation

```python
"""
hanime.tv API Signature Generator
Python implementation of the HMAC-SHA256 signature system.
"""

import hmac
import hashlib
import requests
import time
from typing import Dict


class HanimeSignatureGenerator:
    """Generates HMAC-SHA256 signatures for hanime.tv API authentication."""
    
    SIGN_KEY_URL = 'https://hanime.tv/sign.bin'
    API_BASE = 'https://cached.freeanimehentai.net'
    
    def __init__(self):
        self._key: bytes = None
    
    def fetch_key(self) -> bytes:
        """Fetch the 16-byte HMAC key from sign.bin."""
        response = requests.get(self.SIGN_KEY_URL)
        response.raise_for_status()
        
        key = response.content
        if len(key) != 16:
            raise ValueError(f"Expected 16-byte key, got {len(key)} bytes")
        
        self._key = key
        return key
    
    def generate_signature(self, timestamp: str = None) -> str:
        """
        Generate HMAC-SHA256 signature.
        
        Args:
            timestamp: Unix timestamp in seconds (auto-generated if None)
            
        Returns:
            64-character hex-encoded signature
        """
        if self._key is None:
            self.fetch_key()
        
        if timestamp is None:
            timestamp = str(int(time.time()))
        
        # Generate HMAC-SHA256
        signature = hmac.new(
            self._key,
            timestamp.encode('utf-8'),
            hashlib.sha256
        ).hexdigest()
        
        return signature
    
    def build_headers(self, timestamp: str = None) -> Dict[str, str]:
        """
        Build complete authentication headers.
        
        Args:
            timestamp: Unix timestamp (auto-generated if None)
            
        Returns:
            Dictionary of HTTP headers
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
        Make an authenticated API request.
        
        Args:
            endpoint: API endpoint path (e.g., '/api/v8/guest/videos/3427/manifest')
            
        Returns:
            JSON response as dictionary
        """
        headers = self.build_headers()
        url = f"{self.API_BASE}{endpoint}"
        
        response = requests.get(url, headers=headers)
        response.raise_for_status()
        
        return response.json()


# Example usage
if __name__ == "__main__":
    generator = HanimeSignatureGenerator()
    
    # Generate signature
    signature = generator.generate_signature()
    print(f"Signature: {signature}")
    
    # Make API request
    result = generator.make_request('/api/v8/guest/videos/3427/manifest')
    print(f"Servers: {len(result['servers'])}")
```

### JavaScript/Node.js Implementation

```javascript
/**
 * hanime.tv API Signature Generator
 * Node.js implementation of the HMAC-SHA256 signature system.
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
   * Fetch the 16-byte HMAC key from sign.bin
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
   * Generate HMAC-SHA256 signature
   * @param {string} timestamp - Unix timestamp in seconds
   * @returns {Promise<string>} 64-character hex signature
   */
  async generateSignature(timestamp = null) {
    if (!this.key) {
      await this.fetchKey();
    }
    
    const ts = timestamp || Math.floor(Date.now() / 1000).toString();
    
    // Generate HMAC-SHA256
    const signature = crypto
      .createHmac('sha256', this.key)
      .update(ts)
      .digest('hex');
    
    return signature;
  }

  /**
   * Build complete authentication headers
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
   * Make an authenticated API request
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

// Example usage
async function main() {
  const generator = new HanimeSignatureGenerator();
  
  try {
    // Generate signature
    const signature = await generator.generateSignature();
    console.log('Signature:', signature);
    
    // Make API request
    const result = await generator.makeRequest('/api/v8/guest/videos/3427/manifest');
    console.log('Servers:', result.servers.length);
  } catch (error) {
    console.error('Error:', error.message);
  }
}

main();
```

### Kotlin Implementation

```kotlin
/**
 * hanime.tv API Signature Generator
 * Kotlin implementation for Android/Aniyomi extensions.
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
 * Generates HMAC-SHA256 signatures for hanime.tv API authentication.
 * 
 * The signature is generated using a 16-byte key fetched from sign.bin
 * and a Unix timestamp in seconds.
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
     * Fetch the 16-byte HMAC key from sign.bin.
     * The key is cached for subsequent requests.
     * 
     * @return 16-byte HMAC key
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
     * Generate HMAC-SHA256 signature.
     * 
     * @param key 16-byte HMAC key
     * @param timestamp Unix timestamp in seconds
     * @return 64-character hex-encoded signature
     */
    fun generateSignature(key: ByteArray, timestamp: String): String {
        require(key.size == 16) { "Key must be 16 bytes" }
        
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        
        val signatureBytes = mac.doFinal(timestamp.toByteArray())
        
        // Convert to hex string
        return signatureBytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Build complete authentication headers.
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

// Example usage in Aniyomi extension
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

### cURL Example

```bash
#!/bin/bash

# Fetch the HMAC key
KEY=$(curl -s https://hanime.tv/sign.bin | xxd -p | tr -d '\n' | head -c 32)
echo "Key (hex): $KEY"

# Generate timestamp
TIMESTAMP=$(date +%s)
echo "Timestamp: $TIMESTAMP"

# Generate HMAC-SHA256 signature (requires openssl)
SIGNATURE=$(echo -n "$TIMESTAMP" | openssl dgst -sha256 -hmac "$KEY" -binary | xxd -p | tr -d '\n')
echo "Signature: $SIGNATURE"

# Make API request
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

## Troubleshooting

### Common Issues

#### 1. "Invalid signature" Error

**Cause:** The signature was generated with an incorrect timestamp or message format.

**Solution:**
- Ensure timestamp is in seconds, not milliseconds
- Verify the key was fetched correctly from sign.bin
- Check that the HMAC algorithm is SHA256

#### 2. "Key length mismatch"

**Cause:** The sign.bin endpoint returned unexpected data.

**Solution:**
- Verify the request to sign.bin succeeded
- Check response is exactly 16 bytes
- The endpoint may have changed - verify URL

#### 3. "Expired signature" Error

**Cause:** The timestamp is too old or too far in the future.

**Solution:**
- Use current Unix timestamp
- Ensure system clock is accurate
- Signatures may have a validity window

#### 4. Network Errors When Fetching Key

**Cause:** Connection issues or endpoint unavailable.

**Solution:**
- Implement retry logic with exponential backoff
- Cache the key locally (it appears to be static)
- Check for proxy or firewall issues

#### 5. CORS Errors in Browser

**Cause:** Direct API calls from browser may trigger CORS.

**Solution:**
- Use a backend proxy
- Extract signature from page's JavaScript instead
- Access `window.ssignature` and `window.stime` directly

### Debugging Tips

1. **Log all request headers** to compare with working browser requests
2. **Verify key content** by printing hex representation
3. **Check timestamp synchronization** between client and server
4. **Compare signature generation** with known working examples

### Validation Checklist

Before submitting API requests:

- [ ] Key fetched successfully (16 bytes)
- [ ] Timestamp is current Unix time in seconds
- [ ] HMAC algorithm is SHA256
- [ ] Signature is 64-character hex string
- [ ] All required headers present
- [ ] Header values are properly formatted
- [ ] Referer header is set to hanime.tv

---

## Additional Resources

- [API.md](./API.md) - Complete API documentation
- [HLS.md](./HLS.md) - HLS streaming documentation (if available)

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.1 | 2026-04-19 | Added WASM-based signature generation discovery |
| 1.0 | 2026-04-19 | Initial documentation based on reverse engineering findings |

---

## Notes

This documentation is based on reverse engineering efforts and may change as the hanime.tv API evolves. The sign.bin endpoint and signature format should be monitored for changes.

**Key Discovery:** The same key used for API authentication (`0123456701234567`) is also used for AES-128 encryption of video segments in HLS streams.
