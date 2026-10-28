plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "VeoHentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://veohentai.com"
    }
}
