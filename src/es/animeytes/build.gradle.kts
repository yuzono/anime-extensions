plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeYT.es"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"

    source {
        lang = "es"
        baseUrl = "https://animeyt.es"
    }
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
