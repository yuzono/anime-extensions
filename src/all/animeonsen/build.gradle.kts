plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeOnsen"
    versionCode = 10
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "all"
        baseUrl = "https://www.animeonsen.xyz"
    }
}
