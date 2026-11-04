import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Cimaleek"
    versionCode = 12
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "سيما ليك"
        lang = "ar"
        baseUrl = "https://m.cimaleek.to"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
