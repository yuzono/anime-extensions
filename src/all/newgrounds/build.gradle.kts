plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Newgrounds"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "all"
        baseUrl = "https://www.newgrounds.com"
    }
}
