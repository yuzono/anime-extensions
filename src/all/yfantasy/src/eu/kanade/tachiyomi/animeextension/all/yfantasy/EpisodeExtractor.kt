package eu.kanade.tachiyomi.animeextension.all.yfantasy

import android.util.Log
import eu.kanade.tachiyomi.network.HttpException
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Recovers the segment list of a video that the catalog does not cover, by walking the
 * guest unlock flow of the site: register a throwaway anonymous account, claim the free
 * first hidden segment, spend the starting coin balance on the next one, then read the
 * session, which answers with the same shape as the catalog entries.
 */
class EpisodeExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val baseUrl: String,
) {
    suspend fun getAnime(videoId: String): AnimeDto? = try {
        getSession(videoId, unlockedHeaders(videoId))
    } catch (e: Throwable) {
        Log.e(LOG_TAG, "Could not extract the segments of $videoId", e)
        null
    }

    /**
     * Guest headers holding the two unlocked segments, or the plain source headers when the
     * unlock flow fails: the session lists every segment either way, the unlocked ones just
     * come with a playable url.
     */
    private suspend fun unlockedHeaders(videoId: String): Headers = try {
        val authHeaders = headers.newBuilder()
            .add("Authorization", "Bearer ${authenticate()}")
            .build()

        runCatching { claimFirstSegment(videoId, authHeaders) }
        runCatching { continueToNextSegment(videoId, authHeaders) }

        authHeaders
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Could not unlock the segments of $videoId, listing them locked", e)
        headers
    }

    private suspend fun authenticate(): String {
        val body = AuthRequestDto(UUID.randomUUID().toString()).toJsonRequestBody()

        return retry {
            client.post("$baseUrl/api/auth/anonymous", headers, body).parseAs<AuthDto>()
        }.sessionToken
    }

    /** Only the first hidden segment is claimable, any other one is rejected. */
    private suspend fun claimFirstSegment(videoId: String, authHeaders: Headers) {
        val body = ClaimRequestDto(videoId, "${videoId}_segment_1").toJsonRequestBody()

        client.post("$baseUrl/api/me/guest-first-unlocks/claim", authHeaders, body).close()
    }

    /** Answers 402 once the coin balance no longer covers the next segment. */
    private suspend fun continueToNextSegment(videoId: String, authHeaders: Headers) {
        client.post("$baseUrl/api/videos/$videoId/continue", authHeaders).close()
    }

    private suspend fun getSession(videoId: String, authHeaders: Headers): AnimeDto {
        val url = "$baseUrl/api/videos/$videoId/session".toHttpUrl().newBuilder()
            .addQueryParameter("language", "en")
            .build()

        return retry { client.get(url, authHeaders).parseAs<AnimeDto>() }
    }

    /**
     * The account and session calls fail transiently often enough to be worth retrying,
     * while a client error is a permanent rejection that no retry can turn around.
     */
    private suspend fun <T> retry(block: suspend () -> T): T {
        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                if (e is HttpException && e.code in 400..499) throw e

                Log.w(LOG_TAG, "Attempt ${attempt + 1} failed, retrying", e)
                delay((RETRY_DELAY_MILLIS shl attempt).milliseconds)
            }
        }

        return block()
    }

    companion object {
        private const val LOG_TAG = "YFantasy"
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MILLIS = 1000L
    }
}
