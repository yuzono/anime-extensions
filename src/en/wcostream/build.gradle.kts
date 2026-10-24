plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "WCOStream"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "wcotheme"

    source {
        lang = "en"
        baseUrl = "https://www.wcostream.tv"
    }
}
