plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Oppai Stream"
    versionCode = 7
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://oppai.stream"
    }
}
