plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hentai Saturn"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        name = "HentaiSaturn"
        lang = "it"
        baseUrl = "https://www.hentaisaturn.com"
    }
}
