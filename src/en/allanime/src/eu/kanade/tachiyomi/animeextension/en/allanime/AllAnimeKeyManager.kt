package eu.kanade.tachiyomi.animeextension.en.allanime

import android.content.SharedPreferences
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.parseAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.OkHttpClient
import javax.crypto.spec.SecretKeySpec

/**
 * Owns the "aaReq" key material: fetches partB + epoch from the mkissa page's `window.__aaCrypto`
 * and derives the AES-GCM key with the client mask, cached until the epoch rotates. The mask is
 * extracted from the live JS bundle ([resolveMask]) and re-extracted when it rotates.
 */
class AllAnimeKeyManager(
    private val client: OkHttpClient,
    private val headers: Headers,
    preferences: SharedPreferences,
    private val siteUrl: String,
) {

    class Material(
        val key: SecretKeySpec,
        val epoch: Long,
        val expiresAt: Long,
        val fetchedAt: Long,
    )

    @Volatile
    private var cachedMaterial: Material? = null
    private val materialMutex = Mutex()

    @Volatile
    private var appEntryUrl: String? = null

    private var storedMaskHex by preferences.delegate(PREF_MASK_KEY, "")
    private val maskMutex = Mutex()

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

            val html = client.newCall(GET("$siteUrl/", headers))
                .awaitSuccess().bodyString()

            APP_ENTRY_REGEX.find(html)?.groupValues?.get(1)?.let { appEntryUrl = it }

            val json = AA_CRYPTO_REGEX.find(html)?.groupValues?.get(1)
                ?: throw Exception("Unable to obtain AllAnime crypto material")

            val bootstrap = json.parseAs<AaCryptoBootstrap>()
            val partB = runCatching { Base64.decode(bootstrap.partB, Base64.DEFAULT) }
                .getOrElse { throw Exception("AllAnime crypto material changed; update the extension") }
            require(partB.size >= 32) { "AllAnime crypto material changed; update the extension" }

            // Fixed TTL, not the bootstrap's switchAt: that can already be in the past while the
            // epoch is live, which would refetch the slow mkissa page every playback.
            val now = System.currentTimeMillis()
            Material(
                key = AllAnimeCrypto.deriveKey(mask(), partB),
                epoch = bootstrap.epoch,
                expiresAt = now + MATERIAL_TTL_MS,
                fetchedAt = now,
            ).also { cachedMaterial = it }
        }
    }

    fun aaReq(material: Material): String = AllAnimeCrypto.buildAaReq(material.key, material.epoch, CLIENT_BUILD_ID, STREAM_HASH)

    fun decrypt(tobeparsed: String, material: Material): String? = AllAnimeCrypto.decrypt(tobeparsed, material.key)

    fun invalidate() {
        cachedMaterial = null
    }

    fun isCryptoError(body: String): Boolean = runCatching { body.parseAs<AaApiError>().errors }.getOrNull()
        ?.any { it.extensions?.code?.startsWith("AA_CRYPTO") == true } == true

    suspend fun healMask(): Boolean = resolveMask() != null

    private suspend fun mask(): ByteArray = storedMaskHex.takeIf { it.length == MASK_HEX_LENGTH }?.let(AllAnimeCrypto::hexToBytesOrNull)
        ?: resolveMask()
        ?: throw Exception("Unable to obtain AllAnime crypto material")

    /**
     * Crawls the app's chunks for the crypto chunk and reads its mask, skipping the value
     * we already have (so it doubles as rotation detection). Persists and returns the new
     * mask bytes, or null if none different was found.
     */
    private suspend fun resolveMask(): ByteArray? = maskMutex.withLock {
        val appUrl = appEntryUrl ?: return@withLock null
        val chunkBase = appUrl.substringBeforeLast("/entry/", "") + "/chunks/"
        if (!chunkBase.startsWith("http")) return@withLock null

        val appJs = runCatching {
            client.newCall(GET(appUrl, headers)).awaitSuccess().bodyString()
        }.getOrNull() ?: return@withLock null

        val chunkNames = CHUNK_REF_REGEX.findAll(appJs)
            .map { it.groupValues[1] }
            .distinct()
            .take(MAX_MASK_CHUNKS)

        for (name in chunkNames) {
            val body = runCatching {
                client.newCall(GET(chunkBase + name, headers)).awaitSuccess().bodyString()
            }.getOrNull() ?: continue

            if (!body.contains(CRYPTO_CHUNK_MARKER)) continue

            val hex = HEX64_REGEX.findAll(body)
                .map { it.value }
                .firstOrNull { !it.equals(STREAM_HASH, ignoreCase = true) && !it.equals(storedMaskHex, ignoreCase = true) }
                ?: continue

            val bytes = AllAnimeCrypto.hexToBytesOrNull(hex) ?: continue
            storedMaskHex = hex
            return@withLock bytes
        }
        null
    }

    private fun Material.isExpired(): Boolean = System.currentTimeMillis() >= expiresAt

    companion object {
        // Cosmetic: the server derives its key from the epoch and never validates this.
        private const val CLIENT_BUILD_ID = "12"

        private const val PREF_MASK_KEY = "client_mask_cache"
        private const val MAX_MASK_CHUNKS = 40
        private const val MASK_HEX_LENGTH = 64

        private const val MATERIAL_TTL_MS = 6 * 60 * 60 * 1000L

        private val AA_CRYPTO_REGEX = Regex("""window\.__aaCrypto\s*=\s*(\{[^{}]*\})""")

        // SvelteKit app entry `import("…/entry/app.<hash>.js")` inside the mkissa HTML.
        private val APP_ENTRY_REGEX = Regex("""import\("([^"]*/entry/app\.[^"]*\.js)"\)""")

        // Chunk references inside the app entry's `__vite__mapDeps` array.
        private val CHUNK_REF_REGEX = Regex("""\.\./chunks/([A-Za-z0-9_-]+\.js)""")

        private const val CRYPTO_CHUNK_MARKER = "aaReq"

        // Boundaries stop a 64-hex slice of a longer hash (e.g. sha512) from matching.
        private val HEX64_REGEX = Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])""")
    }
}
