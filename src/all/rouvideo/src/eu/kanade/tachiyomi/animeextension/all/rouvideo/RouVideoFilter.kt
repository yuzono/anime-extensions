package eu.kanade.tachiyomi.animeextension.all.rouvideo

import aniyomi.lib.i18n.Intl
import eu.kanade.tachiyomi.animesource.model.AnimeFilter

internal object RouVideoFilter {

    class CategoryFilter(intl: Intl) :
        UriPartFilter(
            intl["category"],
            arrayOf(
                Tag("<${intl["category"]}>", ""),
                Tag(intl["featured"], FEATURED),
                Tag(intl["other_watching"], WATCHING),
            )
                .plus(categories(intl))
                .plus(Tag(intl["all_videos"], ALL_VIDEOS)),
        )

    class TagFilter(intl: Intl, tags: Tags) :
        UriPartFilter(
            intl["tag"],
            tags,
        )

    class HotSearchFilter(intl: Intl, keywords: Set<Pair<String, String>>) :
        UriPartFilter(
            intl["hot_search"],
            keywords.toTypedArray(),
        )

    class SortFilter(intl: Intl) :
        UriPartFilter(
            intl["sort"],
            arrayOf(
                Pair(intl["latest_recent"], SORT_LATEST_KEY),
                Pair(intl["most_viewed"], SORT_VIEW_KEY),
                Pair(intl["most_liked"], SORT_LIKE_KEY),
            ),
        )

    open class UriPartFilter(displayName: String, private val options: Tags) : AnimeFilter.Select<String>(displayName, options.map { it.first }.toTypedArray()) {
        fun toUriPart() = options[state].second
        fun isEmpty() = options[state].second == ""
        fun isDefault() = state == 0
    }

    fun categories(intl: Intl) = setOf(
        Tag(intl["ChineseAV"], "國產AV"), // ChineseAV
        Tag(intl["ChineseSub"], "中文字幕"), // ChineseSub
        Tag(intl["Madou_Media"], "麻豆傳媒"), // Madou Media
        Tag(intl["Selfie_leaked"], "自拍流出"), // Selfie leaked
        Tag(intl["Tanhua"], "探花"), // Tanhua (Flower exploration - Thám hoa - Check hàng)
        Tag("OnlyFans", "OnlyFans"),
        Tag(intl["JAV"], "日本"), // JAV
    )

    const val FEATURED = "featured"
    const val WATCHING = "watching"
    const val ALL_VIDEOS = "all-videos"

    const val SORT_LATEST_KEY = "createdAt"
    const val SORT_LIKE_KEY = "likeCount"
    const val SORT_VIEW_KEY = "viewCount"
}

typealias Tags = Array<Tag>
typealias Tag = Pair<String, String>
