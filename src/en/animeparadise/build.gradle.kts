import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeParadise"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://www.animeparadise.moe"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
