plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Torrentio (Torrent / Debrid)"
    className = "Torrentio"
    versionCode = 11
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    deeplink {
        host("torrentio.strem.fun")
        path("/anime/..*")
    }
}
