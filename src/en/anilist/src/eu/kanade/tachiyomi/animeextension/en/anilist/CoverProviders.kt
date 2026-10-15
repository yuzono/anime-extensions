package eu.kanade.tachiyomi.animeextension.en.anilist

import keiyoushi.network.get
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import okhttp3.OkHttpClient

class CoverProviders(private val client: OkHttpClient, private val headers: Headers) {
    suspend fun getMALCovers(malId: String): List<String> {
        for (baseUrl in MAL_API_URLS) {
            try {
                val covers = client.get("$baseUrl/anime/$malId/pictures", headers).use { response ->
                    response.parseAs<MALPicturesDto>().data?.mapNotNull { imgs ->
                        imgs.jpg?.let { it.largeImageUrl ?: it.imageUrl ?: it.smallImageUrl }
                    }
                }
                if (!covers.isNullOrEmpty()) return covers
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Try next fallback mirror
            }
        }
        return emptyList()
    }

    suspend fun getFanartCovers(tvdbId: String, type: String): List<String> = try {
        client.get("https://webservice.fanart.tv/v3/$type/$tvdbId?api_key=184e1a2b1fe3b94935365411f919f638", headers).use { response ->
            val fanart = response.parseAs<FanartDto>()
            val posters = if (type == "movies") {
                fanart.movieposter ?: fanart.tvposter
            } else {
                fanart.tvposter ?: fanart.movieposter
            }
            posters?.map { it.url } ?: emptyList()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
}
