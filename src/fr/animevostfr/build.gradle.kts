plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeVostFr"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "fr"
        baseUrl = "https://animevostfr.tv"
    }
}
