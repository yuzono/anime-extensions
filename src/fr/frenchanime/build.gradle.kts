plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "French Anime"
    className = "FrenchAnime"
    versionCode = 34
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "datalifeengine"
    baseUrl = "https://french-anime.com"
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:vidoextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:vudeoextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:upstreamextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamhubextractor"))
    implementation(project(":lib:vidmolyextractor"))
    implementation(project(":lib:voeextractor"))
}
