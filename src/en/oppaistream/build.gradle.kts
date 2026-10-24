plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Oppai Stream"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://oppai.stream"
    }
}
