plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Torrentio (Torrent / Debrid)"
    className = "Torrentio"
    versionCode = 12
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    torrent = true

    deeplink {
        host("torrentio.strem.fun")
        path("/anime/..*")
    }
}
