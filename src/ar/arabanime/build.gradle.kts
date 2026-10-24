plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "ArabAnime"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "ar"
        baseUrl = "https://www.arabanime.net"
    }
}
