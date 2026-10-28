plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Subsplease"
    versionCode = 8
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    torrent = true

    source {
        lang = "all"
        baseUrl = "https://subsplease.org"
    }
}
