package keiyoushi.source.model

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode

class SAnimeUpdate(val anime: SAnime, val episodes: List<SEpisode>)

class SAnimeSeasonUpdate(val anime: SAnime, val seasons: List<SAnime>)

/** Grouped on Animiru, flattened on Anikku. */
class Related(val name: String, val animes: List<SAnime>)
