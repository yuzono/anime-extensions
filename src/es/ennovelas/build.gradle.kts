plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "EnNovelas (Dead)"
    versionCode = 28
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "EnNovelas"
        lang = "es"
        baseUrl = "https://l.ennovelas-tv.com"
    }
}
