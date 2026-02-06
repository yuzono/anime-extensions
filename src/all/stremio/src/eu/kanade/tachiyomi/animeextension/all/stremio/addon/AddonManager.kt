package eu.kanade.tachiyomi.animeextension.all.stremio.addon

import eu.kanade.tachiyomi.animeextension.all.stremio.ResultDto
import eu.kanade.tachiyomi.animeextension.all.stremio.Stremio
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.AddonDto
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.AddonResultDto
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.ManifestDto
import eu.kanade.tachiyomi.util.parallelMapNotNull
import keiyoushi.utils.PreferenceDelegate
import keiyoushi.utils.Source
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.toRequestBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Suppress("SpellCheckingInspection")
class AddonManager(
    addonDelegate: PreferenceDelegate<String>,
    authKeyDelegate: PreferenceDelegate<String>,
) {
    private val addonValue by addonDelegate
    private val authKeyValue by authKeyDelegate

    private var cachedAddons: String? = null
    private var cachedAuthKey: String? = null
    private var addons: List<AddonDto>? = null

    suspend fun getAddons(source: Source): List<AddonDto> {
        val useAddons = addonValue.isNotBlank()
        val hasChanged = when {
            useAddons -> addonValue != cachedAddons
            else -> authKeyValue != cachedAuthKey
        }

        if (hasChanged) {
            addons = when {
                useAddons -> source.getFromPref(addonValue)
                authKeyValue.isNotBlank() -> source.getFromUser(authKeyValue)
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

    private suspend fun Source.getFromPref(addons: String): List<AddonDto> {
        val urls = addons.split("\n")

        return urls.parallelMapNotNull { url ->
            try {
                val manifestUrl = url.replace("stremio://", "https://")
                val manifest = client.get(manifestUrl).parseAs<ManifestDto>()
                AddonDto(
                    transportUrl = manifestUrl,
                    manifest = manifest,
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun Source.getFromUser(authKey: String): List<AddonDto> {
        val body = buildJsonObject {
            put("authKey", authKey)
            put("type", "AddonCollectionGet")
            put("update", true)
        }.toRequestBody()

        return client.post("${Stremio.API_URL}/api/addonCollectionGet", body = body)
            .parseAs<ResultDto<AddonResultDto>>().result.addons
    }
}
