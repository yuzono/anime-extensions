plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Xfani"
    versionCode = 8
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "稀饭动漫"
        lang = "zh"
        baseUrl = "https://anime.xifanacg.com"
    }
}
