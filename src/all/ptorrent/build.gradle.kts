plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "PTorrent (Torrent)"
    className = "PTorrent"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    torrent = true

    deeplink {
        host("www.ptorrents.com")
        host("ptorrents.com")
        path("/..*\\\\.html")
    }
}
