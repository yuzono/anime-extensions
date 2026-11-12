import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Voiranime"
    versionCode = 5
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "fr"
        baseUrl = "https://voir-anime.to"
    }
}

dependencies {
    implementation(project(":lib:universalextractor"))
}
