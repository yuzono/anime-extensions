import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anikage"
    versionCode = 10
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://anikage.cc"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
