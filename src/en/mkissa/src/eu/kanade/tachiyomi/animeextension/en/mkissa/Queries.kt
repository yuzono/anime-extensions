package eu.kanade.tachiyomi.animeextension.en.mkissa

fun buildQuery(queryAction: () -> String): String = queryAction()
    .trimIndent()
    .replace("%", "$")

// The streams endpoint speaks Apollo's automatic persisted queries: it registers a query the first
// time a client sends the text, then keys both that cache and the `qh` field inside `aaReq` on the
// SHA-256 of the exact text. Sending our own query instead of quoting the site's hash means a
// site-side edit to its query can no longer strand the extension on a hash the server has dropped,
// and the reply carries the two fields we read rather than the ~9 kB the site's query asks for.
//
// Nothing reads `show`, but the episode resolver writes to it while resolving `sourceUrls` and
// fails with "Cannot set properties of undefined" when the selection set leaves it out.
val STREAM_QUERY: String = buildQuery {
    """
        query(
            %showId: String!
            %translationType: VaildTranslationTypeEnumType!
            %episodeString: String!
        ) {
            episode(
                showId: %showId
                translationType: %translationType
                episodeString: %episodeString
            ) {
                sourceUrls
                show {
                    _id
                }
            }
        }
    """
}

val STREAM_HASH: String = MKissaCrypto.sha256Hex(STREAM_QUERY)

// Content lane: the site scopes crypto material per content type, picking the lane from the
// query it is about to send. `k7` is anime episodes (`k9` manga chapter pages, `k2` music).
const val ANIME_LANE = "k7"

val POPULAR_QUERY: String = buildQuery {
    """
        query(
                %type: VaildPopularTypeEnumType!
                %size: Int!
                %page: Int
                %dateRange: Int
            ) {
            queryPopular(
                type: %type
                size: %size
                dateRange: %dateRange
                page: %page
            ) {
                total
                recommendations {
                    anyCard {
                        _id
                        name
                        thumbnail
                        englishName
                        nativeName
                        slugTime
                    }
                }
            }
        }
    """
}

val SEARCH_QUERY: String = buildQuery {
    """
        query(
            %search: SearchInput
            %limit: Int
            %page: Int
            %translationType: VaildTranslationTypeEnumType
            %countryOrigin: VaildCountryOriginEnumType
        ) {
            shows(
                search: %search
                limit: %limit
                page: %page
                translationType: %translationType
                countryOrigin: %countryOrigin
            ) {
                pageInfo {
                    total
                }
                edges {
                    _id
                    name
                    thumbnail
                    englishName
                    nativeName
                    slugTime
                }
            }
        }
    """
}

val DETAILS_QUERY = buildQuery {
    """
        query (%_id: String!) {
            show(
                _id: %_id
            ) {
                thumbnail
                description
                type
                season
                score
                genres
                status
                studios
            }
        }
    """
}

val EPISODES_QUERY = buildQuery {
    """
        query (%_id: String!) {
            show(
                _id: %_id
            ) {
                _id
                availableEpisodesDetail
            }
        }
    """
}
