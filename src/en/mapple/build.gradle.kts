import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Mapple"
    versionCode = 9
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://mapple.fun"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
