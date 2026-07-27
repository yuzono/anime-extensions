package eu.kanade.tachiyomi.animeextension.en.allanime

import android.content.SharedPreferences
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.parseAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.crypto.spec.SecretKeySpec

/**
 * Owns the "aaReq" key material.
 *
 * The build's `buildId` and mask seeds are scraped from the live JS bundle ([resolveBuild]) and
 * cached in preferences; together they fold into the 32-byte client mask. That mask signs the
 * `x-aa-boot` token for the crypto bootstrap endpoint, which returns the per-epoch `partB`; the
 * AES-GCM key is `mask XOR partB`.
 */
class AllAnimeKeyManager(
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

    // Both halves live in one preference so a concurrent write can never pair one build's id
    // with another's seeds.
    private var storedBuild by preferences.delegate(PREF_BUILD_KEY, "")

    suspend fun material(forceRefresh: Boolean = false): Material {
        val enteredAt = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedMaterial?.let { if (!it.isExpired()) return it }
        }

        return materialMutex.withLock {
            // Reuse if another coroutine refreshed while we waited on the lock.
            cachedMaterial?.let {
                if (it.fetchedAt > enteredAt || (!forceRefresh && !it.isExpired())) return@withLock it
            }

            val handshake = handshake() ?: throw Exception(MATERIAL_ERROR)

            val partB = runCatching { Base64.decode(handshake.bootstrap.partB, Base64.DEFAULT) }
                .getOrElse { throw Exception(MATERIAL_ERROR) }
            require(partB.size >= 32) { MATERIAL_ERROR }

            // Persisted only now that the server has accepted this build, so a bad parse cannot
            // overwrite a working one and wedge every later launch.
            storedBuild = handshake.build.serialize()

            // Fixed TTL rather than the bootstrap's switchAt: that boundary can already be in the
            // past while the epoch is live, which would refetch on every playback. Real rotations
            // are caught by the AA_CRYPTO_STALE retry, so this is a performance knob only.
            val now = System.currentTimeMillis()
            Material(
                key = AllAnimeCrypto.deriveKey(handshake.mask, partB),
                epoch = handshake.bootstrap.epoch,
                buildId = handshake.build.buildId,
                expiresAt = now + MATERIAL_TTL_MS,
                fetchedAt = now,
            ).also { cachedMaterial = it }
        }
    }

    fun aaReq(material: Material): String = AllAnimeCrypto.buildAaReq(material.key, material.epoch, material.buildId, STREAM_HASH, ANIME_LANE)

    fun decrypt(tobeparsed: String, material: Material): String? = AllAnimeCrypto.decrypt(tobeparsed, material.key)

    fun invalidate() {
        cachedMaterial = null
    }

    /**
     * Drops the cached build so the next [material] call re-scrapes the bundle. Used when the
     * streams API rejects a token the bootstrap was happy to mint, which the bootstrap alone
     * cannot detect. Clearing a flag rather than crawling here keeps parallel episode fetches
     * from each kicking off their own crawl.
     */
    fun invalidateBuild() {
        storedBuild = ""
        cachedMaterial = null
    }

    fun isCryptoError(body: String): Boolean = runCatching { body.parseAs<AaApiError>().errors }.getOrNull()
        ?.any { it.extensions?.code?.startsWith("AA_CRYPTO") == true } == true

    private class Handshake(
        val build: AllAnimeBundle.BuildInfo,
        val mask: ByteArray,
        val bootstrap: AaCryptoBootstrap,
    )

    /**
     * A null [bootstrap] with [stale] set means the server refused this build (403 wrong mask,
     * 404 unknown id) and re-scraping is worth it; without it the failure was a network fault or
     * an unparseable body, which says nothing about whether the build is current.
     */
    private class BootstrapResult(
        val bootstrap: AaCryptoBootstrap?,
        val stale: Boolean,
    )

    /**
     * Exchanges a build for `partB`, escalating only as far as each failure justifies. The epoch
     * retry costs one or two plain API calls, whereas re-scraping starts with the Cloudflare-gated
     * site HTML, which can mean a WebView challenge — so it is worth ruling out a skewed clock
     * first even though the request counts are comparable.
     */
    private suspend fun handshake(): Handshake? {
        val cached = cachedBuild()
        val mask = cached?.let { AllAnimeCrypto.deriveMask(it.buildId, it.seeds) }

        if (cached != null && mask != null) {
            val first = bootstrap(cached.buildId, mask, AllAnimeCrypto.epochCandidates())
            first.bootstrap?.let { return Handshake(cached, mask, it) }
            // Nothing about a timeout implies the build rotated; let the caller's retry loop run.
            if (!first.stale) return null

            // A device clock off by more than the grace window looks exactly like a stale build.
            bootstrap(cached.buildId, mask, AllAnimeCrypto.skewedEpochCandidates()).bootstrap
                ?.let { return Handshake(cached, mask, it) }
        }

        val fresh = resolveBuild() ?: return null
        val freshMask = AllAnimeCrypto.deriveMask(fresh.buildId, fresh.seeds) ?: return null
        return bootstrap(fresh.buildId, freshMask, AllAnimeCrypto.epochCandidates())
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
                .set("x-aa-boot", AllAnimeCrypto.bootToken(mask, buildId, epoch, KEY_GROUP, host, ANIME_LANE))
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

            // A partB minted for another lane would silently derive the wrong key.
            if (bootstrap.k != null && bootstrap.k != ANIME_LANE) continue

            return BootstrapResult(bootstrap, stale = false)
        }
        return BootstrapResult(null, stale = sawStale)
    }

    private fun cachedBuild(): AllAnimeBundle.BuildInfo? {
        val buildId = storedBuild.substringBefore(FIELD_SEPARATOR, "").takeIf(String::isNotEmpty) ?: return null
        val seeds = storedBuild.substringAfter(FIELD_SEPARATOR, "").split(",").filter(String::isNotBlank)
        if (seeds.size != AllAnimeCrypto.SEED_COUNT) return null
        return AllAnimeBundle.BuildInfo(buildId, seeds)
    }

    /**
     * Crawls the app's chunks for the crypto chunk and parses `buildId` + mask seeds out of it.
     * The app entry is always re-read from the site: chunk URLs are content-hashed and immutable,
     * so a rebuild is only ever visible in the HTML.
     */
    private suspend fun resolveBuild(): AllAnimeBundle.BuildInfo? {
        val appUrl = entryUrlFromSite()?.toHttpUrl() ?: return null

        val appJs = runCatching {
            client.newCall(GET(appUrl, headers)).awaitSuccess().bodyString()
        }.getOrNull() ?: return null

        // The ref pattern deliberately matches every relative import, not just `../chunks/`, so a
        // change to the bundle layout cannot hide the crypto chunk. Shared chunks still go first,
        // since that is where it has always lived and the cap has to fall somewhere.
        val chunkRefs = CHUNK_REF_REGEX.findAll(appJs)
            .map { it.groupValues[1] }
            .distinct()
            .sortedByDescending { it.contains("/chunks/") }
            .take(MAX_BUILD_CHUNKS)

        for (ref in chunkRefs) {
            val chunkUrl = appUrl.resolve(ref) ?: continue
            val body = runCatching {
                client.newCall(GET(chunkUrl, headers)).awaitSuccess().bodyString()
            }.getOrNull() ?: continue

            if (!body.contains(CRYPTO_CHUNK_MARKER)) continue

            AllAnimeBundle.parse(body)?.let { return it }
        }
        return null
    }

    /** The mkissa page is Cloudflare-gated; it is only needed to locate the CDN app entry. */
    private suspend fun entryUrlFromSite(): String? {
        val html = runCatching {
            client.newCall(GET("$siteUrl/", headers)).awaitSuccess().bodyString()
        }.getOrNull() ?: return null

        return APP_ENTRY_REGEX.find(html)?.groupValues?.get(1)
    }

    private fun Material.isExpired(): Boolean = System.currentTimeMillis() >= expiresAt

    private fun AllAnimeBundle.BuildInfo.serialize(): String = "$buildId$FIELD_SEPARATOR${seeds.joinToString(",")}"

    companion object {
        private const val MATERIAL_ERROR = "Unable to obtain AllAnime crypto material"

        private const val BOOTSTRAP_PATH = "/client-crypto/v1/bootstrap"

        // 403 invalid_boot_token (mask no longer matches), 404 unknown_build_id (build aged out of
        // the server's rolling window). Anything else is not evidence that our build is wrong.
        private val STALE_CODES = setOf(403, 404)

        // The site buckets its hosts; mkissa.to (and anything unrecognised) maps to "mkissa".
        // Adding a mirror to the site-domain ListPreference means revisiting this.
        private const val KEY_GROUP = "mkissa"

        private const val PREF_BUILD_KEY = "client_build_cache"
        private const val FIELD_SEPARATOR = "|"

        // Bounds the worst-case cost of a re-scrape; the crypto chunk is normally the first ref.
        private const val MAX_BUILD_CHUNKS = 40

        private const val MATERIAL_TTL_MS = 6 * 60 * 60 * 1000L

        // SvelteKit app entry `import("…/entry/app.<hash>.js")` inside the mkissa HTML.
        private val APP_ENTRY_REGEX = Regex("""import\("([^"]*/entry/app\.[^"]*\.js)"\)""")

        // Relative chunk references inside the app entry's `__vite__mapDeps` array, resolved
        // against the entry URL so a change to the bundle's directory layout does not matter.
        private val CHUNK_REF_REGEX = Regex("""["'](\.\.?/[\w./-]+\.js)["']""")

        private const val CRYPTO_CHUNK_MARKER = "aaReq"
    }
}
