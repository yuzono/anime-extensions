plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "SmartAnimes"
    className = "SmartAnimes"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "animestream"
    baseUrl = "https://smartanimes.net"
}

dependencies {
    implementation(project(":lib:googledriveplayerextractor"))
}
