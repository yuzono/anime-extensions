package eu.kanade.tachiyomi.animeextension.en.mkissa

import android.content.SharedPreferences
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parseAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.crypto.spec.SecretKeySpec

/**
 * Owns the "aaReq" key material: `buildId` + mask seeds are scraped from the live JS bundle and
 * fold into the client mask, which signs the bootstrap request for `partB`; the key is
 * `mask XOR partB`.
 */
class MKissaKeyManager(
    private val client: OkHttpClient,
    private val headers: Headers,
    preferences: SharedPreferences,
    private val siteUrl: String,
    private val apiUrl: String,
) {

    class Material(
        val key: SecretKeySpec,
        val epoch: Long,
        val buildId: String,
        val expiresAt: Long,
        val fetchedAt: Long,
    )

    @Volatile
    private var cachedMaterial: Material? = null
    private val materialMutex = Mutex()

    // One preference, so a concurrent write cannot pair one build's id with another's seeds.
    private var storedBuild by preferences.delegate(PREF_BUILD_KEY, "")

    suspend fun material(forceRefresh: Boolean = false): Material {
        val enteredAt = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedMaterial?.let { if (!it.isExpired()) return it }
        }

        return materialMutex.withLock {
            cachedMaterial?.let {
                if (it.fetchedAt > enteredAt || (!forceRefresh && !it.isExpired())) return@withLock it
            }

            val handshake = handshake() ?: throw Exception(MATERIAL_ERROR)

            val partB = runCatching { Base64.decode(handshake.bootstrap.partB, Base64.DEFAULT) }
                .getOrElse { throw Exception(MATERIAL_ERROR) }
            require(partB.size >= 32) { MATERIAL_ERROR }

            // Only after the server accepted it, so a bad parse cannot wedge every later launch.
            storedBuild = handshake.build.serialize()

            // Not the bootstrap's switchAt: it can already be past while the epoch is live.
            val now = System.currentTimeMillis()
            Material(
                key = MKissaCrypto.deriveKey(handshake.mask, partB),
                epoch = handshake.bootstrap.epoch,
                buildId = handshake.build.buildId,
                expiresAt = now + MATERIAL_TTL_MS,
                fetchedAt = now,
            ).also { cachedMaterial = it }
        }
    }

    fun aaReq(material: Material): String = MKissaCrypto.buildAaReq(material.key, material.epoch, material.buildId, STREAM_HASH, ANIME_LANE)

    fun decrypt(tobeparsed: String, material: Material): String? = MKissaCrypto.decrypt(tobeparsed, material.key)

    fun invalidate() {
        cachedMaterial = null
    }

    /** Used when the streams API rejects a token the bootstrap minted, which it cannot detect. */
    fun invalidateBuild() {
        storedBuild = ""
        cachedMaterial = null
    }

    fun isCryptoError(body: String): Boolean = runCatching { body.parseAs<AaApiError>().errors }.getOrNull()
        ?.any { it.extensions?.code?.startsWith("AA_CRYPTO") == true } == true

    /**
     * The server's own complaint — a captcha gate, a rate limit — for responses it refuses outright.
     * Null while a crypto error is present, since fresh material can still rescue those.
     */
    fun apiErrorMessage(body: String): String? {
        if (isCryptoError(body)) return null
        val message = runCatching { body.parseAs<AaApiError>().errors }.getOrNull()
            ?.firstNotNullOfOrNull { it.message }
            ?: return null
        // The bare code says nothing to someone whose episode just refused to load, and the gate is
        // on the network rather than the account, so there is nothing to sign in to or re-install.
        return if (message == CAPTCHA_ERROR) {
            "MKissa is rate limiting this network ($CAPTCHA_ERROR). Browsing still works; " +
                "streams should return on their own after a while."
        } else {
            "MKissa: $message"
        }
    }

    private class Handshake(
        val build: MKissaBundle.BuildInfo,
        val mask: ByteArray,
        val bootstrap: AaCryptoBootstrap,
    )

    /** [stale] distinguishes "server refused this build" from a network fault. */
    private class BootstrapResult(
        val bootstrap: AaCryptoBootstrap?,
        val stale: Boolean,
    )

    /** Re-scraping starts at the Cloudflare-gated HTML, so cheaper causes are ruled out first. */
    private suspend fun handshake(): Handshake? {
        val cached = cachedBuild()
        val mask = cached?.let { MKissaCrypto.deriveMask(it.buildId, it.seeds) }

        if (cached != null && mask != null) {
            val first = bootstrap(cached.buildId, mask, MKissaCrypto.epochCandidates())
            first.bootstrap?.let { return Handshake(cached, mask, it) }
            if (!first.stale) return null

            // A clock off by more than the grace window looks exactly like a stale build.
            bootstrap(cached.buildId, mask, MKissaCrypto.skewedEpochCandidates()).bootstrap
                ?.let { return Handshake(cached, mask, it) }
        }

        val fresh = resolveBuild() ?: return null
        val freshMask = MKissaCrypto.deriveMask(fresh.buildId, fresh.seeds) ?: return null
        return bootstrap(fresh.buildId, freshMask, MKissaCrypto.epochCandidates())
            .bootstrap?.let { Handshake(fresh, freshMask, it) }
    }

    /** `GET /client-crypto/v1/bootstrap?buildId=&k=`, gated by an HMAC of the client mask. */
    private suspend fun bootstrap(buildId: String, mask: ByteArray, epochs: List<Long>): BootstrapResult {
        val host = siteUrl.toHttpUrl().host
        val url = "${apiUrl.trimEnd('/')}$BOOTSTRAP_PATH".toHttpUrl().newBuilder()
            .addQueryParameter("buildId", buildId)
            .addQueryParameter("k", ANIME_LANE)
            .build()

        var sawStale = false
        for (epoch in epochs) {
            val requestHeaders = headers.newBuilder()
                .set("x-build-id", buildId)
                .set("x-aa-boot", MKissaCrypto.bootToken(mask, buildId, epoch, KEY_GROUP, host, ANIME_LANE))
                .set("Origin", siteUrl)
                .set("Referer", "$siteUrl/")
                .build()

            val response = runCatching { client.newCall(GET(url, requestHeaders)).await() }.getOrNull()
                ?: return BootstrapResult(null, stale = false)

            if (!response.isSuccessful) {
                response.close()
                if (response.code in STALE_CODES) sawStale = true
                continue
            }

            val bootstrap = runCatching { response.parseAs<AaCryptoBootstrap>() }.getOrNull()
                ?: return BootstrapResult(null, stale = false)

            // A partB from another lane would silently derive the wrong key.
            if (bootstrap.k != null && bootstrap.k != ANIME_LANE) continue

            return BootstrapResult(bootstrap, stale = false)
        }
        return BootstrapResult(null, stale = sawStale)
    }

    private fun cachedBuild(): MKissaBundle.BuildInfo? {
        val buildId = storedBuild.substringBefore(FIELD_SEPARATOR, "").takeIf(String::isNotEmpty) ?: return null
        val seeds = storedBuild.substringAfter(FIELD_SEPARATOR, "").split(",").filter(String::isNotBlank)
        if (seeds.size != MKissaCrypto.SEED_COUNT) return null
        return MKissaBundle.BuildInfo(buildId, seeds)
    }

    /** The entry is re-read every time: chunk URLs are immutable, so a rebuild only shows in HTML. */
    private suspend fun resolveBuild(): MKissaBundle.BuildInfo? {
        val appUrl = entryUrlFromSite()?.toHttpUrl() ?: return null

        val appJs = runCatching {
            client.newCall(GET(appUrl, headers)).awaitSuccess().bodyString()
        }.getOrNull() ?: return null

        // Shared chunks first: that is where it has always lived.
        val chunkRefs = CHUNK_REF_REGEX.findAll(appJs)
            .map { it.groupValues[1] }
            .distinct()
            .sortedByDescending { it.contains("/chunks/") }
            .take(MAX_BUILD_CHUNKS)
            .toList()

        // In batches rather than all at once: the chunks run to about a megabyte each, so fetching
        // the whole list in parallel would pull tens of megabytes to use one of them, while walking
        // them one at a time pays a full round trip per miss.
        for (batch in chunkRefs.chunked(BUILD_CHUNK_BATCH)) {
            val found = batch.parallelCatchingMapNotNull { ref ->
                val chunkUrl = appUrl.resolve(ref) ?: return@parallelCatchingMapNotNull null
                val body = client.newCall(GET(chunkUrl, headers)).awaitSuccess().bodyString()
                if (!body.contains(CRYPTO_CHUNK_MARKER)) return@parallelCatchingMapNotNull null
                MKissaBundle.parse(body)
            }
            // Order is preserved, so this stays the first hit in the site's own chunk order.
            found.firstOrNull()?.let { return it }
        }
        return null
    }

    /** Cloudflare-gated; only needed to locate the CDN app entry. */
    private suspend fun entryUrlFromSite(): String? {
        val html = runCatching {
            client.newCall(GET("$siteUrl/", headers)).awaitSuccess().bodyString()
        }.getOrNull() ?: return null

        return APP_ENTRY_REGEX.find(html)?.groupValues?.get(1)
    }

    private fun Material.isExpired(): Boolean = System.currentTimeMillis() >= expiresAt

    private fun MKissaBundle.BuildInfo.serialize(): String = "$buildId$FIELD_SEPARATOR${seeds.joinToString(",")}"

    companion object {
        private const val MATERIAL_ERROR = "Unable to obtain MKissa crypto material"

        private const val CAPTCHA_ERROR = "NEED_CAPTCHA"

        private const val BOOTSTRAP_PATH = "/client-crypto/v1/bootstrap"

        // 403 invalid_boot_token, 404 unknown_build_id.
        private val STALE_CODES = setOf(403, 404)

        // The site buckets its hosts; adding a mirror to the domain pref means revisiting this.
        private const val KEY_GROUP = "mkissa"

        private const val PREF_BUILD_KEY = "client_build_cache"
        private const val FIELD_SEPARATOR = "|"

        private const val MAX_BUILD_CHUNKS = 40
        private const val BUILD_CHUNK_BATCH = 4

        private const val MATERIAL_TTL_MS = 6 * 60 * 60 * 1000L

        private val APP_ENTRY_REGEX = Regex("""import\("([^"]*/entry/app\.[^"]*\.js)"\)""")

        private val CHUNK_REF_REGEX = Regex("""["'](\.\.?/[\w./-]+\.js)["']""")

        private const val CRYPTO_CHUNK_MARKER = "aaReq"
    }
}
