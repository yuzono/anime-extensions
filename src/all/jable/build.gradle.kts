plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Jable"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    listOf("zh", "en", "ja").forEach {
        source {
            lang = it
            baseUrl = "https://jable.tv"
        }
    }
}
