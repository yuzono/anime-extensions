import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "FilmPalast"
    versionCode = 28
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "de"
        baseUrl = "https://filmpalast.to"
    }
}

dependencies {
    implementation(project(":lib:voeextractor"))
}
