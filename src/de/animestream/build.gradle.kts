plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime-Stream"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "de"
        id = 314593699490737069L
        baseUrl = "https://anime-stream.to"
    }
}
