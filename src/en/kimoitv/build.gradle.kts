plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "KimoiTV"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://kimoitv.com"
    }
}
