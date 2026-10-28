plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniZone"
    versionCode = 15
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "all"
        baseUrl = "https://anizone.to"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
