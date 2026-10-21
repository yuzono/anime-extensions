package eu.kanade.tachiyomi.animeextension.zh.xfani

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

abstract class SelectFilter(name: String, private val options: Array<Pair<String, String>>) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selected
        get() = options[state].second
}

abstract class TagFilter(name: String, values: Array<String>) :
    SelectFilter(
        name,
        values.mapIndexed { index, s ->
            if (index == 0) {
                s to ""
            } else {
                s to s
            }
        }.toTypedArray(),
    )

class TypeFilter(
    kv: Array<Pair<String, String>> = arrayOf(
        "全部" to "",
        "连载新番" to "1",
        "完结旧番" to "2",
        "剧场版" to "3",
    ),
) : SelectFilter("频道", kv)

class ClassFilter(
    tags: Array<String> = arrayOf(
        "全部",
        "搞笑",
        "原创",
        "小说改",
        "恋爱",
        "百合",
        "漫画改",
        "奇幻",
        "战斗",
        "校园",
    ),
) : TagFilter("类型", tags)

class VersionFilter(
    tags: Array<String> = arrayOf(
        "全部",
        "tv",
        "movie",
        "ova",
        "oad",
        "web",
    ),
) : TagFilter("形式", tags)

class SortFilter(
    kv: Array<Pair<String, String>> = arrayOf(
        "按上映日期（最新）" to "release_date",
        "按热门" to "view_count",
        "按评分" to "bangumi_score",

    ),
) : SelectFilter("排序", kv)

class YearFilter : SelectFilter("年份", arrayOf("全部" to "") + (2026 downTo 2007).map { it.toString() to it.toString() }.toTypedArray())
