plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "PTorrent (Torrent)"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    torrent = true

    source {
        lang = "all"
        baseUrl = "https://www.ptorrents.com"
    }

    deeplink {
        host("www.ptorrents.com")
        host("ptorrents.com")
        path("/..*\\\\.html")
    }
}
