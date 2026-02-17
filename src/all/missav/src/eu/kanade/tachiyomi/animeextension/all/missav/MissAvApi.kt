package eu.kanade.tachiyomi.animeextension.all.missav

import android.util.Log
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MissAvApi {
    internal fun generateUUID(): String {
        // Use Java's built-in UUID generator for better security and simplicity
        return UUID.randomUUID().toString()
    }

    fun searchURL(uuid: String): String {
        val path = "/missav-default/search/users/$uuid/items/?frontend_timestamp=${System.currentTimeMillis()}"

        val signedPath = generateHMACSignature(path, PUBLIC_TOKEN)

        return "$API_URL$signedPath"
    }

    fun recommURL(recommId: String): String {
        val path = "/missav-default/recomms/next/items/$recommId?frontend_timestamp=${System.currentTimeMillis()}"

        val signedPath = generateHMACSignature(path, PUBLIC_TOKEN)

        return "$API_URL$signedPath"
    }

    fun relatedURL(): String {
        val path = "/missav-default/batch/?frontend_timestamp=${System.currentTimeMillis()}"

        val signedPath = generateHMACSignature(path, PUBLIC_TOKEN)

        return "$API_URL$signedPath"
    }

    fun searchData(query: String): String = kotlinx.serialization.json.buildJsonObject {
        addRequestParams(scenario = "search", searchQuery = query)
    }.toString()

    val recommData
        get() = """{"count":$RESULT_COUNT,"cascadeCreate":true}"""

    fun relatedData(uuid: String, entryId: String): String {
        fun buildRequestObject(scenario: String) = kotlinx.serialization.json.buildJsonObject {
            put("method", "POST")
            put("path", "/recomms/items/$entryId/items/")
            putJsonObject("params") {
                addRequestParams(scenario = scenario, targetUserId = uuid)
            }
        }

        return kotlinx.serialization.json.buildJsonObject {
            put(
                "requests",
                kotlinx.serialization.json.buildJsonArray {
                    add(buildRequestObject("desktop-watch-next-side"))
                    add(buildRequestObject("desktop-watch-next-bottom"))
                },
            )
            put("distinctRecomms", true)
        }.toString()
    }

    private fun JsonObjectBuilder.addRequestParams(
        scenario: String,
        targetUserId: String? = null,
        searchQuery: String? = null,
    ) {
        targetUserId?.let { put("targetUserId", it) }
        searchQuery?.let { put("searchQuery", it) }
        put("count", RESULT_COUNT)
        put("scenario", scenario)
        put("returnProperties", true)
        put(
            "includedProperties",
            kotlinx.serialization.json.buildJsonArray {
                add("title_en")
                add("dm")
            },
        )
        put("cascadeCreate", true)
    }

    private fun generateHMACSignature(data: String, @Suppress("SameParameterValue") key: String): String = try {
        val secretKeySpec = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA1")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(secretKeySpec)

        val hashBytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        val hexString = hashBytes.joinToString("") { "%02x".format(it) }

        "$data&frontend_sign=$hexString"
    } catch (e: Exception) {
        Log.e("MissAvApi", "HMAC signature generation failed", e)
        // Fallback to original path if signing fails
        data
    }

    const val RESULT_COUNT = 24
    private const val API_URL = "https://client-rapi-missav.recombee.com"
    private const val PUBLIC_TOKEN = "Ikkg568nlM51RHvldlPvc2GzZPE9R4XGzaH9Qj4zK9npbbbTly1gj9K4mgRn0QlV"
}
