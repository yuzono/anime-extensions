import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Aniweek"
    versionCode = 20
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "ko"
        baseUrl = "https://aniweek.com"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
