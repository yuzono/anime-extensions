package eu.kanade.tachiyomi.animesource.model

class Hoster(
    val hosterUrl: String = "",
    val hosterName: String = "",
    val videoList: List<Video>? = null,
    val internalData: String = "",
    val lazy: Boolean = false,
) {
    companion object {
        const val NO_HOSTER_LIST = "no_hoster_list"

        fun List<Video>.toHosterList(): List<Hoster> = listOf(
            Hoster(
                hosterUrl = "",
                hosterName = NO_HOSTER_LIST,
                videoList = this,
            ),
        )
    }
}
