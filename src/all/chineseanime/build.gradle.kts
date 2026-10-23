plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "ChineseAnime"
    className = "ChineseAnime"
    versionCode = 28
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"
    baseUrl = "https://www.chineseanime.in"
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:playlistutils"))
}
