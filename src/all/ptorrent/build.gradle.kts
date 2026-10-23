plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "PTorrent (Torrent)"
    className = "PTorrent"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    torrent = true

    deeplink {
        host("nyaa.si")
        path("/view/..*")
    }
}
