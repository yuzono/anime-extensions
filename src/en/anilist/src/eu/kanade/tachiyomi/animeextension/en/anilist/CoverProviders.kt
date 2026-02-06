package eu.kanade.tachiyomi.animeextension.en.anilist

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient

class CoverProviders(private val client: OkHttpClient, private val headers: Headers) {
    fun getMALCovers(malId: String): List<String> {
        val picturesResponse = client.newCall(
            GET("https://api.jikan.moe/v4/anime/$malId/pictures", headers),
        ).execute().parseAs<MALPicturesDto>()

        return picturesResponse.data.mapNotNull { imgs ->
            imgs.jpg.let { it.largeImageUrl ?: it.imageUrl ?: it.smallImageUrl }
        }
    }

    fun getFanartCovers(tvdbId: String, type: String): List<String> {
        val picturesResponse = client.newCall(
            GET("https://webservice.fanart.tv/v3/$type/$tvdbId?api_key=184e1a2b1fe3b94935365411f919f638", headers),
        ).execute()

        return picturesResponse.parseAs<FanartDto>().tvposter?.map { it.url } ?: emptyList()
    }
}
