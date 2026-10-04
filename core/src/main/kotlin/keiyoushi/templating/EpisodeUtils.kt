package keiyoushi.templating

import eu.kanade.tachiyomi.animesource.model.SEpisode

fun List<SEpisode>.sortByEpisodeNumber(): List<SEpisode> =
    sortedByDescending { it.episode_number }

fun List<SEpisode>.sortByEpisodeNumberAsc(): List<SEpisode> =
    sortedBy { it.episode_number }
