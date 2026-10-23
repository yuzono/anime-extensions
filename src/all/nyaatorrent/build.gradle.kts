plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Nyaa (Torrent)"
    className = "NyaaFactory"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    torrent = true

    deeplink {
        host("nyaa.si")
        path("/view/..*")
    }
}
