import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime-Loads"
    versionCode = 29
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "de"
        id = 655155856096L
        baseUrl = "https://www.anime-loads.org"
    }
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
}
