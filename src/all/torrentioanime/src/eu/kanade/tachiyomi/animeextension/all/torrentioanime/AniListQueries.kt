package eu.kanade.tachiyomi.animeextension.all.torrentioanime

private fun String.toQuery() = this.trimIndent().replace("%", "$")

fun anilistQuery() = """
    query (
        %page: Int,
        %perPage: Int,
        %sort: [MediaSort],
        %search: String,
        %genres: [String],
        %year: String,
        %seasonYear: Int,
        %season: MediaSeason,
        %format: [MediaFormat],
        %status: [MediaStatus],
    ) {
        Page(page: %page, perPage: %perPage) {
            pageInfo {
                currentPage
                hasNextPage
            }
            media(
                type: ANIME,
                sort: %sort,
                search: %search,
                status_in: %status,
                genre_in: %genres,
                startDate_like: %year,
                seasonYear: %seasonYear,
                season: %season,
                format_in: %format,
                isAdult: false
            ) {
                id
                isAdult
                title {
                    romaji
                    english
                    native
                }
                coverImage {
                    extraLarge
                    large
                }
                description
                status
                tags {
                    name
                }
                genres
                studios {
                    nodes {
                        name
                    }
                }
                countryOfOrigin
                isAdult
            }
        }
    }
""".toQuery()

fun anilistLatestQuery() = """
    query (%page: Int, %perPage: Int, %sort: [AiringSort]) {
        Page(page: %page, perPage: %perPage) {
            pageInfo {
                currentPage
                hasNextPage
                total
            }
            airingSchedules(
                airingAt_greater: 0
                airingAt_lesser: ${System.currentTimeMillis() / 1000 - 10000}
                sort: %sort
            ) {
                episode
                airingAt
                media {
                    id
                    isAdult
                    title {
                        romaji
                        english
                        native
                    }
                    coverImage {
                        extraLarge
                        large
                    }
                    description
                    status
                    tags {
                        name
                    }
                    genres
                    studios {
                        nodes {
                            name
                        }
                    }
                    countryOfOrigin
                    isAdult
                }
            }
        }
    }
""".toQuery()
fun anilistRelationsQuery() = """
    query (%id: Int) {
        Media(id: %id) {
            relations {
                edges {
                    relationType
                    node {
                        id
                        type
                        title {
                            romaji
                            english
                            native
                        }
                        coverImage {
                            extraLarge
                            large
                        }
                        status
                    }
                }
            }
        }
    }
""".toQuery()
