plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "WcoAnimeSub"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "wcotheme"

    source {
        lang = "en"
        baseUrl = "https://www.wcoanimesub.tv"
    }
}
