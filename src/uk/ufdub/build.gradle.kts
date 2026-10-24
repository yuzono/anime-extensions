plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "UFDub"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "uk"
        baseUrl = "https://ufdub.com/anime"
    }
}
