plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "MyReadingManga"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    listOf(
        "ar", "id", "bg", "zh-hk", "zh", "zh-tw", "cs", "da", "en", "fil", "fi",
        "nl", "fr", "de", "el", "he", "hi", "hu", "it", "ja", "ko", "ms", "no",
        "pl", "pt-BR", "ro", "ru", "es", "sv", "th", "tr", "vi",
    ).forEach {
        source {
            lang = it
            baseUrl = "https://myreadingmanga.info"
        }
    }
}
