package eu.kanade.tachiyomi.animeextension.all.stremio.addon

import eu.kanade.tachiyomi.animeextension.all.stremio.ResultDto
import eu.kanade.tachiyomi.animeextension.all.stremio.Stremio
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.AddonDto
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.AddonResultDto
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.ManifestDto
import keiyoushi.utils.PreferenceDelegate
import keiyoushi.utils.Source
import keiyoushi.utils.get
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AddonManager(
    addonDelegate: PreferenceDelegate<String>,
    authKeyDelegate: PreferenceDelegate<String>,
) {
    private val addonValue by addonDelegate
    private val authKeyValue by authKeyDelegate

    private var cachedAddons: String? = null
    private var cachedAuthKey: String? = null
    private var addons: List<AddonDto>? = null

    context(source: Source)
    suspend fun getAddons(): List<AddonDto> {
        val useAddons = addonValue.isNotBlank()
        val hasChanged = when {
            useAddons -> addonValue != cachedAddons
            else -> authKeyValue != cachedAuthKey
        }

        if (hasChanged) {
            addons = when {
                useAddons -> getFromPref(addonValue)
                authKeyValue.isNotBlank() -> getFromUser(authKeyValue)
                else -> throw Exception("Addons must be manually added if not logged in")
            }

            if (useAddons) {
                cachedAddons = addonValue
                cachedAuthKey = null
            } else {
                cachedAuthKey = authKeyValue
                cachedAddons = null
            }
        }

        return addons ?: emptyList()
    }

    context(source: Source)
    private suspend fun getFromPref(addons: String): List<AddonDto> {
        val urls = addons.split("\n")

        return urls.parallelMapNotNull { url ->
            try {
                val manifestUrl = url.replace("stremio://", "https://")
                val manifest = source.client.get(manifestUrl).parseAs<ManifestDto>()
                AddonDto(
                    transportUrl = manifestUrl,
                    manifest = manifest,
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    context(source: Source)
    private suspend fun getFromUser(authKey: String): List<AddonDto> = with(source) {
        val body = buildJsonObject {
            put("authKey", authKey)
            put("type", "AddonCollectionGet")
            put("update", true)
        }.toJsonRequestBody()

        client.post("${Stremio.API_URL}/api/addonCollectionGet", body = body)
            .parseAs<ResultDto<AddonResultDto>>().result.addons
    }
}
