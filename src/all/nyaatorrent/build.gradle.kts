plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Nyaa (Torrent)"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
    torrent = true

    source {
        lang = "all"
        baseUrl {
            custom("https://nyaa.si")
        }
    }
    source {
        name = "Nyaa Sukebein (Torrent)"
        lang = "all"
        baseUrl {
            custom("https://sukebei.nyaa.si")
        }
    }

    deeplink {
        host("nyaa.si")
        path("/view/..*")
    }
}
