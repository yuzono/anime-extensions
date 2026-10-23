plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HDFilmCehennemi"
    className = "HDFilmCehennemi"
    versionCode = 34
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    deeplink {
        host("www.hdfilmcehennemi.nl")
        path("/..*")
    }
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlistutils"))
}
