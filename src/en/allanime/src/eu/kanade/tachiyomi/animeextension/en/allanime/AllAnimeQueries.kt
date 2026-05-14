package eu.kanade.tachiyomi.animeextension.en.allanime

fun buildQuery(queryAction: () -> String): String = queryAction()
    .trimIndent()
    .replace("%", "$")

const val STREAM_HASH = "d405d0edd690624b66baba3068e0edc3ac90f1597d898a1ec8db4e5c43c00fec"

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
