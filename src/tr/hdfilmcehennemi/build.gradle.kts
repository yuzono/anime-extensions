plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HDFilmCehennemi"
    versionCode = 34
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "tr"
        baseUrl = "https://www.hdfilmcehennemi.nl"
    }

    deeplink {
        path("/..*")
    }
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlistutils"))
}
