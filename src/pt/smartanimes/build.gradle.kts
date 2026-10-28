plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "SmartAnimes"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
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
