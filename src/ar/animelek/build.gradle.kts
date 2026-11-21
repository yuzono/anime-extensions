import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeLek"
    versionCode = 42
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "ar"
        baseUrl = "https://animelek.me"
    }
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:vidbomextractor"))
}
