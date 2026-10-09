package eu.kanade.tachiyomi.animeextension.en.anilist

import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient

class CoverProviders(private val client: OkHttpClient, private val headers: Headers) {
    fun getMALCovers(malId: String): List<String> {
        for (baseUrl in MAL_API_URLS) {
            try {
                val covers = client.newCall(
                    GET("$baseUrl/anime/$malId/pictures", headers),
                ).execute().use { response ->
                    if (!response.isSuccessful) return@use null

                    response.parseAs<MALPicturesDto>().data?.mapNotNull { imgs ->
                        imgs.jpg?.let { it.largeImageUrl ?: it.imageUrl ?: it.smallImageUrl }
                    }
                }
                if (!covers.isNullOrEmpty()) return covers
            } catch (_: Exception) {
                // Try next fallback mirror
            }
        }
        return emptyList()
    }

    fun getFanartCovers(tvdbId: String, type: String): List<String> {
        return client.newCall(
            GET("https://webservice.fanart.tv/v3/$type/$tvdbId?api_key=184e1a2b1fe3b94935365411f919f638", headers),
        ).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()

            try {
                val fanart = response.parseAs<FanartDto>()
                val posters = if (type == "movies") {
                    fanart.movieposter ?: fanart.tvposter
                } else {
                    fanart.tvposter ?: fanart.movieposter
                }
                posters?.map { it.url } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
