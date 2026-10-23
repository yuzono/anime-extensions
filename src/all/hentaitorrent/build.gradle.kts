plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hentai Torrent (Torrent)"
    className = "HentaiTorrent"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    torrent = true

    deeplink {
        host("www.hentaitorrents.com")
        host("hentaitorrents.com")
        path("/..*\\\\.html")
    }
}
