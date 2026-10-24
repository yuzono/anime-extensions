plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Nyaa (Torrent)"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    torrent = true

    source {
        lang = "all"
        baseUrl("https://nyaa.si") {
            withCustom = true
        }
    }
    source {
        name = "Nyaa Sukebein (Torrent)"
        lang = "all"
        baseUrl("https://sukebei.nyaa.si") {
            withCustom = true
        }
    }

    deeplink {
        host("nyaa.si")
        path("/view/..*")
    }
}
