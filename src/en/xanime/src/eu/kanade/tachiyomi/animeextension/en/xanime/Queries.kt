package eu.kanade.tachiyomi.animeextension.en.xanime

import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class Queries(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val headers: Headers,
) {

    private val searchQuery = $$"""
        query get_q27($select: SearchAnime_Select) {
          get_q27(select: $select) {
            paging { total pages page next prev }
            items { id data {
              ani_id info_title info_slug urlCover600 urlCoverOri
            } }
          }
        }
    """

    private val detailsQuery = $$"""
    query Get_animesNode($getAnimesNodeId: String!) {
        get_q02(id: $getAnimesNodeId) {
            id
            data {
                ani_id aniPath info_title info_filmdesc
                info_meta_status info_meta_genre info_meta_season info_meta_year
                info_meta_duration info_meta_rating info_meta_scores
                info_meta_studios info_meta_type info_meta_dateAiredBegin
                info_meta_dateAiredEnd info_alternative_titles { type title }
                urlCover600 urlCoverOri info_cover_name bgimg_url
                info_slug ani_id_mal al_id
                episodesNodes_last(amount: 1) {
                    id data {
                        ani_id ep_id ep_index ep_title epPath
                        date_create date_update
                    }
                }
            }
        }
    }
"""

    private val relationsQuery = $$"""
    query Get_animesNode($getAnimesNodeId: String!) {
        get_q02(id: $getAnimesNodeId) {
            data {
                ani_id
            }
            relations {
                title
                ani_id
                urlCover600
            }
        }
    }
    """

    private val episodesQuery = $$"""
        query get_q01($select: AnimesEpisodesList_Select) {
            get_q01(select: $select){
                paging { total pages page next prev }
                items { id data {
                    ani_id ep_id ep_index  ep_title epPath date_create
                    date_update ep_sub_index
                    sourcesNode_list { id data { sou_id src_type } }
                } }
            }
        }
    """

    private val videoUrlQuery = $$"""
        query get_q07($select: Episodes_Select) {
            get_q07(select: $select) {
                id
                data {
                    epPath
                    sourcesNode_list {
                        id data {
                            sou_id src_name src_type souPath
                            m3u8_lists { name iframe }
                            track { label kind default local trackPath }
                        }
                    }
                }
            }
        }
    """

    suspend fun searchAnime(select: SearchSelect): SearchResponse {
        val payload = GraphQlPayload(
            query = searchQuery,
            variables = SearchVariables(select).toJsonElement(),
        )
        return executeRequest(payload)
    }

    suspend fun getAnimeDetails(aniId: String): SearchItem? {
        val payload = GraphQlPayload(
            query = detailsQuery,
            variables = DetailsVariables(aniId).toJsonElement(),
        )
        return executeRequest<NodeResponse>(payload).node
    }

    fun getRelatedAnimeRequest(aniId: String): Request {
        val payload = GraphQlPayload(
            query = relationsQuery,
            variables = DetailsVariables(aniId).toJsonElement(),
        )
        return POST(
            "${baseUrlProvider()}/z2/",
            headers = headers,
            body = payload.toJsonRequestBody(),
        )
    }

    suspend fun getEpisodes(aniId: String, page: Int = 1): EpisodesResponse {
        val payload = GraphQlPayload(
            query = episodesQuery,
            variables = EpisodeVariables(EpisodeSelect(aniId, page = page)).toJsonElement(),
        )
        return executeRequest(payload)
    }

    suspend fun getVideoUrl(epId: String): VideoUrlResponse {
        val payload = GraphQlPayload(
            query = videoUrlQuery,
            variables = VideoVariables(VideoSelect(epId)).toJsonElement(),
        )
        return executeRequest(payload)
    }

    private suspend inline fun <reified T> executeRequest(payload: GraphQlPayload): T {
        val request = POST(
            "${baseUrlProvider()}/z2/",
            headers = headers,
            body = payload.toJsonRequestBody(),
        )
        return client.newCall(request).awaitSuccess().parseAs<GraphQlResponse<T>>().data
            ?: throw Exception("Unexpected API response")
    }
}
