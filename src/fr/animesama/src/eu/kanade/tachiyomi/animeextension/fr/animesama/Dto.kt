package eu.kanade.tachiyomi.animeextension.fr.animesama

import kotlinx.serialization.Serializable

class AnimePageData(
    val name: String,
    val thumbnailUrl: String?,
    val status: Int,
    val description: String,
    val genres: String,
    val seasons: List<Pair<String, String>>, // (seasonName, seasonStem)
)

class EpisodePage(
    val voice: String,
    val path: String,
    val rootUrl: String,
    val languesDispo: List<String>,
    val jeton: String,
    val slots: List<List<String>>,
    val epsMeta: Map<String, String?>, // lien id -> host
    val lecteurs: Map<String, String>, // host -> display name
)

@Serializable
class EpisodeVoiceData(
    val voice: String,
    val jeton: String,
    val path: String,
    val players: List<PlayerToken>,
)

@Serializable
class PlayerToken(
    val token: String, // "lien:<id>" or empty when the player lacks this episode
    val family: String = "", // site's own player name
)

@Serializable
class LienResponse(val u: String = "")

@Serializable
class LecteurInfo(val nom: String = "")
