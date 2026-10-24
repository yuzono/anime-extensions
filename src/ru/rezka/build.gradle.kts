plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HDRezka"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "ru"
        baseUrl = "https://hdrezka.me"
        skipCodeGen = true
    }
}
