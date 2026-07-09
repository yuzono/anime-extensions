package keiyoushi.lib.tlsspoof

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient

/**
 * Wires the upstream Conscrypt JSSE provider + OkHttp's `MODERN_TLS` cipher
 * profile into an [OkHttpClient.Builder].
 *
 * **Usage:** callers obtain a "spoofed" client by passing a builder (usually
 * `someClient.newBuilder()`) to [applyTo]. The function:
 *
 * 1. Calls [TlsSpoof.install] to install the upstream Conscrypt JSSE provider
 *    at JCA position 1, if not already installed. From this point on, every
 *    `SSLContext.getInstance("TLS")` OkHttp creates internally uses Conscrypt
 *    (Chromium's JSSE build) instead of Android's bundled variant.
 * 2. Filters the builder's `connectionSpecs` down to OkHttp's `MODERN_TLS`
 *    profile — TLS 1.2/1.3 only, AEAD-only cipher suites, the same set
 *    Chrome 131 offers (Chrome's exact byte ordering is owned by Conscrypt's
 *    ClientHello builder, not by OkHttp — see [TlsSpoof] for why a per-byte
 *    Chrome JA3 isn't reachable from pure JVM). `CLEARTEXT` is preserved so
 *    plain-HTTP calls still work.
 *
 * The mutation is **idempotent** — calling [applyTo] twice on the same
 * builder produces the same result as calling it once.
 *
 * **What this does NOT do.** It does not disable cert pinning or trust
 * verification — the spoofed handshake still validates against the system
 * trust store. If a caller needs trusted-CA-only connections they retain
 * that property after [applyTo]; if they need to bypass cert verification
 * for testing they must do that separately.
 */
public object SpoofedTlsSupport {

    /**
     * Mutates [builder] to install the Chromium-adjacent TLS profile on top
     * of whatever base configuration the builder carries (timeouts,
     * interceptors, cookie jars, etc. — all preserved). Returns the same
     * builder for chaining.
     *
     * After this call, the caller's network traffic will use the upstream
     * Conscrypt TLS stack and present a Chromium-adjacent JA3/JA4 fingerprint
     * to remote peer servers.
     *
     * **Idempotent** and safe to call multiple times. Concurrent calls are
     * safe — TlsSpoof.install is synchronized, and `connectionSpecs(...)`
     * is a simple setter.
     */
    @JvmStatic
    public fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        // Step 1: install the upstream Conscrypt JSSE provider.
        // Idempotent; synchronized internally. If installation fails (e.g.
        // org.conscrypt:conscrypt-android missing from the classpath), the
        // ConnectionSpec below is still applied but the wire fingerprint will
        // remain Android's default. TlsSpoof.install logs the warning.
        TlsSpoof.install()

        // Step 2: filter connectionSpecs to TLS-modern + CLEARTEXT.
        //
        // OkHttp 5.x's `ConnectionSpec` ctor and `Builder` ctor are both
        // Kotlin `internal` — neither is reachable from a non-`okhttp`-
        // package module without reflection. The only public entry points
        // are the named constant specs (`MODERN_TLS`/`RESTRICTED_TLS`/
        // `COMPATIBLE_TLS`/`CLEARTEXT`), so we use `MODERN_TLS` directly.
        //
        // That's not a fingerprint compromise. `ConnectionSpec` only gates
        // which cipher suites are *offered*; the byte-level ordering of those
        // suites and the TLS extensions (GREASE, ALPS, X25519MLKEM768) in the
        // ClientHello are emitted by Conscrypt's native ClientHello builder,
        // which we just installed at JCA position 1. `MODERN_TLS` enables the
        // same AEAD TLS 1.2 + all three TLS 1.3 suites Chrome 131 offers; no
        // CBC/3DES/RC4. OkHttp's preferred suite order doesn't reach the wire
        // because Conscrypt reorders by its compile-time cipher profile.
        builder.connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))

        return builder
    }
}
