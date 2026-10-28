plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HDFilmCehennemi"
    versionCode = 35
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "tr"
        baseUrl = "https://www.hdfilmcehennemi.nl"
    }

    deeplink {
        path("/..*")
    }

    // Old domains: keep in sync with LEGACY_HOSTS in HDFilmCehennemi.kt
    deeplink {
        host("www.hdfilmcehennemi.ws")
        host("www.hdfilmcehennemi.la")
        host("www.hdfilmcehennemi.us")
        host("www.hdfilmcehennemi.fun")
        host("www.hdfilmcehennemi.de")
        host("www.hdfilmcehennemi.life")
        path("/..*")
    }
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlistutils"))
}
