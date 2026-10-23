plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Torrentio Anime (Torrent / Debrid)"
    className = "Torrentio"
    versionCode = 23
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    deeplink {
        host("torrentio.strem.fun")
        path("/anime/..*")
    }
}
