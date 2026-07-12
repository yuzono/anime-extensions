package keiyoushi.templating

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Handles downloading, caching, and querying the anime-offline-database
 * from [manami-project/anime-offline-database](https://github.com/manami-project/anime-offline-database).
 *
 * The database is stored as a JSON file in [Context.filesDir] with a
 * 7-day expiry. An in-memory index is built for fast AniList ID lookups.
 *
 * This is used by [MetadataProvider] to populate [MetaproviderContext.nativeIds]
 * before sub-providers run.
 */
open class AnimeDatabaseCache(
    private val context: Context?,
    private val client: OkHttpClient?,
) {
    companion object {
        private const val DB_FILENAME = "anime-offline-database.json"
        private const val INDEX_FILENAME = "anime-index.json"
        private const val PREFS_NAME = "anime_database_cache"
        private const val KEY_LAST_UPDATED = "last_updated"
        private const val REFRESH_DAYS = 7L
        private val DB_URL = "https://raw.githubusercontent.com/manami-project/anime-offline-database/master/anime-offline-database-minified.json"
    }

    private val dbFile: File get() = File(context.filesDir, DB_FILENAME)
    private val indexFile: File get() = File(context.filesDir, INDEX_FILENAME)
    private val prefs: SharedPreferences get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var inMemoryIndex: JSONObject? = null

    @Volatile
    private var malToAnilistIndex: JSONObject? = null

    @Volatile
    private var kitsuToAnilistIndex: JSONObject? = null

    private val lock = Any()

    suspend fun getIndex(): JSONObject {
        inMemoryIndex?.let { return it }

        synchronized(lock) {
            inMemoryIndex?.let { return it }

            if (needsDownload()) {
                downloadDatabase()
                buildIndex()
                updateTimestamp()
            } else if (!indexFile.exists()) {
                buildIndex()
            }

            return readIndex().also { inMemoryIndex = it }
        }
    }

    open suspend fun resolveNativeIds(anilistId: Int): Map<String, Int> {
        val index = getIndex()
        val entry = index.optJSONObject(anilistId.toString()) ?: return emptyMap()
        val nativeIds = mutableMapOf<String, Int>()
        entry.optInt("malId").takeIf { it > 0 }?.let { nativeIds["mal"] = it }
        entry.optInt("kitsuId").takeIf { it > 0 }?.let { nativeIds["kitsu"] = it }
        entry.optInt("anidbId").takeIf { it > 0 }?.let { nativeIds["anidb"] = it }
        return nativeIds
    }

    open suspend fun resolveAnilistIdFromNative(nativeType: String, nativeId: Int): Int? {
        return when (nativeType) {
            "mal" -> resolveAnilistIdFromMal(nativeId)
            "kitsu" -> resolveAnilistIdFromKitsu(nativeId)
            else -> null
        }
    }

    open suspend fun resolveAnilistIdFromMal(malId: Int): Int? {
        val index = getMalToAnilistIndex()
        return index.optInt(malId.toString()).takeIf { it > 0 }
    }

    open suspend fun resolveAnilistIdFromKitsu(kitsuId: Int): Int? {
        val index = getKitsuToAnilistIndex()
        return index.optInt(kitsuId.toString()).takeIf { it > 0 }
    }

    private suspend fun getMalToAnilistIndex(): JSONObject {
        malToAnilistIndex?.let { return it }

        // Ensure main index is built first
        getIndex()

        synchronized(lock) {
            malToAnilistIndex?.let { return it }
            val index = buildReverseIndex("malId")
            malToAnilistIndex = index
            return index
        }
    }

    private suspend fun getKitsuToAnilistIndex(): JSONObject {
        kitsuToAnilistIndex?.let { return it }

        // Ensure main index is built first
        getIndex()

        synchronized(lock) {
            kitsuToAnilistIndex?.let { return it }
            val index = buildReverseIndex("kitsuId")
            kitsuToAnilistIndex = index
            return index
        }
    }

    private fun buildReverseIndex(nativeIdField: String): JSONObject {
        val mainIndex = readIndex()
        val reverseIndex = JSONObject()

        for (anilistIdStr in mainIndex.keys()) {
            val entry = mainIndex.optJSONObject(anilistIdStr) ?: continue
            val nativeId = entry.optInt(nativeIdField).takeIf { it > 0 } ?: continue
            reverseIndex.put(nativeId.toString(), anilistIdStr.toIntOrNull() ?: continue)
        }

        return reverseIndex
    }

    open suspend fun getMetadata(anilistId: Int): ExtensionMetadata? {
        val index = getIndex()
        val entry = index.optJSONObject(anilistId.toString()) ?: return null
        return ExtensionMetadata(
            title = entry.optString("title").ifEmpty { null },
            description = entry.optString("description").ifEmpty { null },
            thumbnailUrl = entry.optString("thumbnail").ifEmpty { null },
            genre = entry.optString("genre").ifEmpty { null },
            status = entry.optInt("status", -1).takeIf { it >= 0 },
        )
    }

    private fun needsDownload(): Boolean {
        if (!dbFile.exists()) return true
        if (!indexFile.exists()) return true

        val fileAge = System.currentTimeMillis() - dbFile.lastModified()
        if (fileAge > TimeUnit.DAYS.toMillis(REFRESH_DAYS)) return true

        val lastUpdated = prefs.getLong(KEY_LAST_UPDATED, 0)
        return lastUpdated == 0L || (System.currentTimeMillis() - lastUpdated > TimeUnit.DAYS.toMillis(REFRESH_DAYS))
    }

    private fun updateTimestamp() {
        prefs.edit().putLong(KEY_LAST_UPDATED, System.currentTimeMillis()).apply()
    }

    private fun downloadDatabase() {
        val httpClient = client ?: return
        val request = Request.Builder()
            .url(DB_URL)
            .header("Accept", "application/json")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return

        val body = response.body?.string() ?: return
        dbFile.writeText(body)
    }

    private fun buildIndex() {
        if (!dbFile.exists()) return

        val raw = dbFile.readText()
        val db = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }

        val data = db.optJSONArray("data") ?: return
        val index = JSONObject()

        for (i in 0 until data.length()) {
            val anime = data.optJSONObject(i) ?: continue

            // Extract AniList ID from sources
            val sources = anime.optJSONArray("sources") ?: continue
            val anilistId = extractAnilistId(sources) ?: continue

            // Extract other native IDs
            val malId = anime.optInt("malId", 0)
            val kitsuId = anime.optInt("kitsuId", 0)
            val anidbId = anime.optInt("anidbId", 0)

            // Extract metadata
            val title = anime.optString("title").ifEmpty { null }
            val thumbnail = anime.optString("picture").ifEmpty { null }
            val status = mapStatus(anime.optString("status").ifEmpty { null })
            val genres = anime.optJSONArray("tags")
            val genre = if (genres != null && genres.length() > 0) {
                (0 until genres.length()).mapNotNull { genres.optString(it) }.joinToString(", ")
            } else {
                null
            }

            val entry = JSONObject().apply {
                put("title", title)
                put("thumbnail", thumbnail)
                put("genre", genre)
                put("status", status ?: -1)
                put("malId", malId)
                put("kitsuId", kitsuId)
                put("anidbId", anidbId)
            }
            index.put(anilistId.toString(), entry)
        }

        indexFile.writeText(index.toString())
    }

    private fun extractAnilistId(sources: org.json.JSONArray): Int? {
        for (i in 0 until sources.length()) {
            val url = sources.optString(i)
            val match = Regex("""anilist\.co/anime/(\d+)""").find(url)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    private fun readIndex(): JSONObject {
        if (!indexFile.exists()) return JSONObject()
        return try {
            JSONObject(indexFile.readText())
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun mapStatus(status: String?): Int? = when (status) {
        "ONGOING" -> 1 // SAnime.ONGOING
        "FINISHED" -> 2 // SAnime.COMPLETED
        "UPCOMING" -> 3 // SAnime.LICENSED
        "HIATUS" -> 5 // SAnime.ON_HIATUS
        else -> null
    }
}
