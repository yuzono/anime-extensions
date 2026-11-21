import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "SmartAnimes"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "14"
    theme = "animestream"

    source {
        lang = "pt-BR"
        baseUrl = "https://smartanimes.net"
    }
}

dependencies {
    implementation(project(":lib:googledriveplayerextractor"))
}
