plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Xnxx"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "all"
        baseUrl = "https://www.xnxx.com"
    }
}
