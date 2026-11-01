plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "haho.moe"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://haho.moe"
    }
}
