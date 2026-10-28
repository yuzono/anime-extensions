plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hentai Torrent (Torrent)"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    torrent = true

    source {
        lang = "all"
        baseUrl = "https://www.hentaitorrents.com"
    }

    deeplink {
        host("www.hentaitorrents.com")
        host("hentaitorrents.com")
        path("/..*\\\\.html")
    }
}
