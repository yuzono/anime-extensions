plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Torbox"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "all"
        versionId = 2
        baseUrl = "https://api.torbox.app"
    }
}
