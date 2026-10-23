plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Torrentio Anime (Torrent / Debrid)"
    className = "Torrentio"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    torrent = true

    deeplink {
        host("torrentio.strem.fun")
        path("/anime/..*")
    }
}
