import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniZone"
    versionCode = 16
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "all"
        baseUrl = "https://anizone.to"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
