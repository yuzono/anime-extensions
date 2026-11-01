package eu.kanade.tachiyomi.animeextension.zh.girigirilove

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import java.util.Calendar

open class SelectFilter(
    name: String,
    private val options: Array<Pair<String, String>>,
) : AnimeFilter.Select<String>(
    name,
    options.map { it.first }.toTypedArray(),
) {
    val selected get() = options[state].second
}

class TypeFilter :
    SelectFilter(
        "频道",
        arrayOf(
            "全部" to "1",
            "日番" to "2",
            "美番" to "3",
            "劇場版" to "21",
            "真人番劇" to "20",
            "BD副音軌" to "24",
            "演唱會&周邊活動&其他" to "26",
        ),
    )

class GenreFilter :
    SelectFilter(
        "类型",
        arrayOf(
            "全部" to "",
            "喜剧" to "喜剧",
            "爱情" to "爱情",
            "恐怖" to "恐怖",
            "动作" to "动作",
            "科幻" to "科幻",
            "剧情" to "剧情",
            "战争" to "战争",
            "奇幻" to "奇幻",
            "冒险" to "冒险",
            "悬疑" to "悬疑",
            "校园" to "校园",
            "后宫" to "后宫",
            "热血" to "热血",
            "战斗" to "战斗",
            "推理" to "推理",
            "治愈" to "治愈",
            "美食" to "美食",
            "竞技" to "竞技",
            "励志" to "励志",
            "职场" to "职场",
            "历史" to "历史",
            "歌舞" to "歌舞",
            "传记" to "传记",
            "运动" to "运动",
            "灾难" to "灾难",
            "武侠" to "武侠",
            "古装" to "古装",
            "犯罪" to "犯罪",
            "惊悚" to "惊悚",
            "家庭" to "家庭",
            "魔幻" to "魔幻",
            "经典" to "经典",
            "神话" to "神话",
            "清新" to "清新",
            "原创" to "原创",
            "日常" to "日常",
            "乙女" to "乙女",
            "女性" to "女性",
            "特摄" to "特摄",
            "耽美" to "耽美",
            "百合" to "百合",
        ),
    )

class YearFilter :
    SelectFilter(
        "年份",
        (listOf("全部" to "") + (Calendar.getInstance().get(Calendar.YEAR) downTo 2000).map { it.toString() to it.toString() }).toTypedArray(),
    )

class SortFilter :
    SelectFilter(
        "排序",
        arrayOf(
            "时间" to "time",
            "人气" to "hits",
            "评分" to "score",
        ),
    )
