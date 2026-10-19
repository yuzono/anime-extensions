package eu.kanade.tachiyomi.animeextension.en.anilist

private fun String.toQuery() = this.trimIndent().replace("%", "$")

fun getSortQuery() = """
query (
    %page: Int,
    %perPage: Int,
    %isAdult: Boolean,
    %type: MediaType,
    %sort: [MediaSort],
    %status: MediaStatus,
    %search: String,
    %genres: [String],
    %year: String,
    %seasonYear: Int,
    %season: MediaSeason,
    %format: [MediaFormat],
    %countryOfOrigin: CountryCode,
) {
    Page (page: %page, perPage: %perPage) {
        pageInfo {
            hasNextPage
        }
        media (
            isAdult: %isAdult,
            type: %type,
            sort: %sort,
            status: %status,
            search: %search,
            genre_in: %genres,
            startDate_like: %year,
            seasonYear: %seasonYear,
            season: %season,
            format_in: %format,
            countryOfOrigin: %countryOfOrigin,
        ) {
            id
            title {
                romaji
                english
                native
            }
            coverImage {
                extraLarge
                large
                medium
            }
        }
    }
}
""".toQuery()

fun getPersonalListQuery() = """
query (
    %userName: String,
    %type: MediaType,
    %status: MediaListStatus,
    %page: Int,
    %perPage: Int,
) {
    Page(page: %page, perPage: %perPage) {
        pageInfo {
            hasNextPage
        }
        mediaList(userName: %userName, type: %type, status: %status) {
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
                    medium
                }
            }
        }
    }
}
""".toQuery()

fun getDetailsQuery() = """
query media(%id: Int, %type: MediaType) {
  Media(id: %id, type: %type) {
    id
    title {
        romaji
        english
        native
    }
    coverImage {
        extraLarge
        large
        medium
    }
    description
    season
    seasonYear
    format
    status(version: 2)
    genres
    episodes
    format
    studios {
      edges {
        isMain
        node {
          id
          name
        }
      }
    }
  }
}
""".toQuery()

fun getEpisodeQuery() = $$"""
query media($id: Int, $type: MediaType) {
  Media(id: $id, type: $type) {
    idMal
    status
    episodes
    nextAiringEpisode {
      episode
    }
  }
}
""".trimIndent()
