plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimesDrive"
    versionCode = 7
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"

    source {
        name = "Animes Drive"
        lang = "pt-BR"
        baseUrl = "https://animesdrive.cloud"
    }

    // Old domains: keep in sync with LEGACY_HOSTS in AnimesDrive.kt
    deeplink {
        host("animesdrive.online")
        path("/.*/..*")
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:playlistutils"))
}
