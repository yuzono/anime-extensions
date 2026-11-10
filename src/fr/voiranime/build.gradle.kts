import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Voiranime"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "fr"
        baseUrl = "https://voir-anime.to"
    }
}

dependencies {
    implementation(project(":lib:universalextractor"))
}
