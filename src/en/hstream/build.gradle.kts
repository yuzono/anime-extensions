plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hstream"
    versionCode = 13
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        versionId = 2
        baseUrl = "https://hstream.moe"
    }

    deeplink {
        path("/hentai/..*")
    }
}
