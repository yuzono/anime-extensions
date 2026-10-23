plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "VoirCartoon"
    className = "VoirCartoon"
    versionCode = 16
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://voircartoon.com"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
