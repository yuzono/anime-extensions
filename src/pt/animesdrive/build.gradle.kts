plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimesDrive"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"

    source {
        name = "Animes Drive"
        lang = "pt-BR"
        baseUrl = "https://animesdrive.online"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:playlistutils"))
}
