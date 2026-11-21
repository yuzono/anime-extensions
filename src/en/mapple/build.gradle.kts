import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Mapple"
    versionCode = 10
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://mapple.fun"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
