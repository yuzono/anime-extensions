plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimesOnlineCloud"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"

    source {
        name = "Animes Online Cloud"
        lang = "pt-BR"
        baseUrl = "https://animesonline.cloud"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:playlistutils"))
}
