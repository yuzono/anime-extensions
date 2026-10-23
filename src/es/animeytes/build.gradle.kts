plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeYT.es"
    className = "AnimeYTES"
    versionCode = 23
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"
    baseUrl = "https://animeyt.pro"
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:burstcloudextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:universalextractor"))
}
