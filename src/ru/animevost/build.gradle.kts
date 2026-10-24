plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animevost"
    versionCode = 10
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "ru"
        baseUrl = "https://animevost.org"
    }
    source {
        name = "Animevost Mirror"
        lang = "ru"
        baseUrl = "https://v13.vost.pw"
    }
}
