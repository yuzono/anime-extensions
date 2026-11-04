package eu.kanade.tachiyomi.animeextension.en.pornhub

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object PornhubFilters {

    open class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
        defaultIndex: Int = 0,
    ) : AnimeFilter.Select<String>(
        name,
        options.map { it.first }.toTypedArray(),
        defaultIndex,
    ) {
        val selectedValue: String
            get() = options[state].second
    }

    class SortFilter :
        SelectFilter(
            "Sort by",
            arrayOf(
                "Featured / Default" to "",
                "Most Recent" to "mr",
                "Most Viewed" to "mv",
                "Top Rated" to "tr",
                "Hot / Trending" to "ht",
                "Longest" to "lg",
            ),
        )

    class OrientationFilter :
        SelectFilter(
            "Orientation",
            arrayOf(
                "Straight (Default)" to "",
                "Gay" to "gay",
                "Transgender" to "transgender",
            ),
        )

    class QualityFilter :
        SelectFilter(
            "Quality",
            arrayOf(
                "All" to "",
                "720p+ HD" to "1",
            ),
        )

    class DurationFilter :
        SelectFilter(
            "Duration",
            arrayOf(
                "All" to "",
                "1-10 minutes" to "10",
                "10-20 minutes" to "20",
                "20+ minutes" to "20+",
            ),
        )

    class ProductionFilter :
        SelectFilter(
            "Production",
            arrayOf(
                "All" to "",
                "Professional" to "professional",
                "Homemade / Amateurs" to "homemade",
            ),
        )

    class CategoryFilter :
        SelectFilter(
            "Category",
            CATEGORIES,
        )

    private val CATEGORIES = arrayOf(
        "All Categories" to "",
        "Amateur" to "3",
        "Anal" to "35",
        "Anime / Hentai" to "88",
        "Asian" to "1",
        "BBW" to "6",
        "Big Ass" to "4",
        "Big Dick" to "8",
        "Big Tits" to "8",
        "Blowjob" to "13",
        "Bondage / BDSM" to "10",
        "Compilation" to "105",
        "Cosplay" to "201",
        "Creampie" to "15",
        "Cuckold" to "141",
        "Cumshot" to "16",
        "Double Penetration" to "72",
        "Ebony" to "17",
        "Exclusive" to "115",
        "Feet" to "93",
        "Fetish" to "27",
        "Fingering" to "592",
        "Funny" to "32",
        "Gangbang" to "80",
        "Handjob" to "21",
        "Hardcore" to "19",
        "HD Porn" to "38",
        "Interracial" to "25",
        "Japanese" to "111",
        "Korean" to "101",
        "Latina" to "26",
        "Lesbian" to "27",
        "Massage" to "78",
        "Masturbation" to "22",
        "Mature" to "28",
        "MILF" to "29",
        "Orgy" to "2",
        "Parody" to "96",
        "Pissing" to "43",
        "POV" to "41",
        "Public" to "24",
        "Pussy Licking" to "131",
        "Reality" to "42",
        "Red Head" to "44",
        "Roleplay" to "81",
        "Romantic" to "532",
        "Rough Sex" to "67",
        "Russian" to "99",
        "School" to "89",
        "SFW" to "109",
        "Sleep" to "562",
        "Small Tits" to "59",
        "Solo Female" to "482",
        "Solo Male" to "91",
        "Squirt" to "65",
        "Step Fantasy" to "121",
        "Striptease" to "33",
        "Tattooed" to "542",
        "Teen (18+)" to "37",
        "Threesome" to "63",
        "Toys" to "492",
        "Verified Amateurs" to "138",
        "Vintage" to "48",
        "VR" to "103",
        "Webcam" to "61",
    )
}
