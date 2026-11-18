package eu.kanade.tachiyomi.animesource.model

import keiyoushi.annotation.AnimiruOnly

@AnimiruOnly
class AnimeRelation(
    val name: String,
    val animes: List<SAnime>,
)
