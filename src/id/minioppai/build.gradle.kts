plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "MiniOppai"
    className = "MiniOppai"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "animestream"
    baseUrl = "https://minioppai.org"
}

dependencies {
    implementation(project(":lib:gdriveplayerextractor"))
    implementation(project(":lib:unpacker"))
}
