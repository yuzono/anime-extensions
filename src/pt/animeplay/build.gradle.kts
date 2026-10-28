plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimePlay"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"

    source {
        name = "Anime Play"
        lang = "pt-BR"
        baseUrl = "https://animeplay.cloud"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:playlistutils"))
}
