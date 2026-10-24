plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Xvideos"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "all"
        baseUrl = "https://www.xvideos.com"
    }
}
