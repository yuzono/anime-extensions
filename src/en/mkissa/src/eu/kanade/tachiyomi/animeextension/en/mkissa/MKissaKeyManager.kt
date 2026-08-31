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

            storedBuild = handshake.build.serialize()

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

    fun invalidateBuild() {
        storedBuild = ""
        cachedMaterial = null
    }

    fun isCryptoError(body: String): Boolean = runCatching { body.parseAs<AaApiError>().errors }.getOrNull()
        ?.any { it.extensions?.code?.startsWith("AA_CRYPTO") == true } == true

    fun apiErrorMessage(body: String): String? {
        if (isCryptoError(body)) return null
        val message = runCatching { body.parseAs<AaApiError>().errors }.getOrNull()
            ?.firstNotNullOfOrNull { it.message }
            ?: return null
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

    private class BootstrapResult(
        val bootstrap: AaCryptoBootstrap?,
        val stale: Boolean,
        val mask: ByteArray? = null,
    )

    private suspend fun handshake(): Handshake? {
        val cached = cachedBuild()
        val cachedMask = cached?.let { MKissaCrypto.deriveMask(it.buildId, it.seeds) }

        if (cached != null && cachedMask != null) {
            val first = bootstrap(cached.buildId, cachedMask, MKissaCrypto.epochCandidates())
            first.bootstrap?.let { return Handshake(cached, cachedMask, it) }
            if (!first.stale) return null

            val second = bootstrap(cached.buildId, cachedMask, MKissaCrypto.skewedEpochCandidates())
            second.bootstrap?.let { return Handshake(cached, cachedMask, it) }
        }

        val fresh = resolveBuild() ?: return null
        val freshMask = MKissaCrypto.deriveMask(fresh.buildId, fresh.seeds) ?: return null
        val freshResult = bootstrap(fresh.buildId, freshMask, MKissaCrypto.epochCandidates())
        return freshResult.bootstrap?.let { Handshake(fresh, freshMask, it) }
    }

    private suspend fun bootstrap(buildId: String, mask: ByteArray, epochs: List<Long>): BootstrapResult {
        val host = siteUrl.toHttpUrl().host
        val url = "${apiUrl.trimEnd('/')}$BOOTSTRAP_PATH".toHttpUrl().newBuilder()
            .addQueryParameter("buildId", buildId)
            .addQueryParameter("k", ANIME_LANE)
            .build()

        var sawStale = false
        for (epoch in epochs) {
            val bootToken = MKissaCrypto.bootToken(mask, buildId, epoch, KEY_GROUP, host, ANIME_LANE)
            val requestHeaders = headers.newBuilder()
                .set("x-build-id", buildId)
                .set("x-aa-boot", bootToken)
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
                ?: continue

            if (bootstrap.k != null && bootstrap.k != ANIME_LANE) continue

            return BootstrapResult(bootstrap, stale = false, mask = mask)
        }
        return BootstrapResult(null, stale = sawStale)
    }

    private fun cachedBuild(): MKissaBundle.BuildInfo? {
        val buildId = storedBuild.substringBefore(FIELD_SEPARATOR, "").takeIf(String::isNotEmpty) ?: return null
        val seeds = storedBuild.substringAfter(FIELD_SEPARATOR, "").split(",").filter(String::isNotBlank)
        if (seeds.size != MKissaCrypto.SEED_COUNT) return null
        return MKissaBundle.BuildInfo(buildId, seeds)
    }

    private suspend fun resolveBuild(): MKissaBundle.BuildInfo? {
        val appUrl = entryUrlFromSite()?.toHttpUrl() ?: return null

        val appJs = runCatching {
            client.newCall(GET(appUrl, headers)).awaitSuccess().bodyString()
        }.getOrNull() ?: return null

        val chunkRefs = CHUNK_REF_REGEX.findAll(appJs)
            .map { it.groupValues[1] }
            .distinct()
            .sortedByDescending { it.contains("/chunks/") }
            .take(MAX_BUILD_CHUNKS)
            .toList()

        for (batch in chunkRefs.chunked(BUILD_CHUNK_BATCH)) {
            val found = batch.parallelCatchingMapNotNull { ref ->
                val chunkUrl = appUrl.resolve(ref) ?: return@parallelCatchingMapNotNull null
                val body = client.newCall(GET(chunkUrl, headers)).awaitSuccess().bodyString()
                if (!body.contains(CRYPTO_CHUNK_MARKER)) return@parallelCatchingMapNotNull null
                MKissaBundle.parse(body)
            }
            found.firstOrNull()?.let { return it }
        }
        return null
    }

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

        private val STALE_CODES = setOf(403, 404)

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
