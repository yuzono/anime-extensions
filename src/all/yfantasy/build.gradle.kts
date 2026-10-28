plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "YFantasy"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "all"
        baseUrl = "https://yfantasy.me"
    }
}
