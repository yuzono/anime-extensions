plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hanime1.me"
    versionCode = 7
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "zh"
        baseUrl = "https://hanime1.me"
    }
}
