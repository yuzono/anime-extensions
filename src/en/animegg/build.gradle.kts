plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeGG"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://www.animegg.org"
    }
}
