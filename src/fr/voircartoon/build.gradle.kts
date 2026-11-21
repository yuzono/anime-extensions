import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "VoirCartoon"
    versionCode = 18
    contentWarning = ContentWarning.MIXED
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "fr"
        baseUrl = "https://voircartoon.com"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
