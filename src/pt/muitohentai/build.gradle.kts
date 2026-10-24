plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Muito Hentai"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "pt-BR"
        baseUrl = "https://www.muitohentai.com"
    }
}
