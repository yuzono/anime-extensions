package eu.kanade.tachiyomi.animeextension.en.anikage

fun buildQuery(queryAction: () -> String): String = queryAction()
    .trimIndent()
    .replace("%", "$")

val QUERY: String = buildQuery {
    """
    query (
          %page: Int = 1
          %perPage: Int = 30
          %type: MediaType = ANIME
          %search: String
          %format_in: [MediaFormat]
          %status: MediaStatus
          %countryOfOrigin: CountryCode
          %season: MediaSeason
          %seasonYear: Int
          %genre_in: [String]
          %tag_in: [String]
          %sort: [MediaSort]
          %isAdult: Boolean
        ) {
          Page(page: %page, perPage: %perPage) {
            pageInfo {
              total
              perPage
              currentPage
              lastPage
              hasNextPage
            }
            media(
              type: %type
              sort: %sort
              season: %season
              seasonYear: %seasonYear
              search: %search
              genre_in: %genre_in
              tag_in: %tag_in
              format_in: %format_in
              status: %status
              countryOfOrigin: %countryOfOrigin
              isAdult: %isAdult
            ) {
              id
              title {
                english
                romaji
              }
              coverImage {
                extraLarge
                color
              }
              startDate {
                year
                month
                day
              }
              bannerImage
              season
              seasonYear
              description
              type
              format
              status(version: 2)
              episodes
              duration
              chapters
              volumes
              genres
              isAdult
              averageScore
              popularity
              nextAiringEpisode {
                airingAt
                timeUntilAiring
                episode
              }
              mediaListEntry {
                id
                status
              }
            }
          }
        }
    """
}
