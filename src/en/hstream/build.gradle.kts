plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hstream"
    versionCode = 12
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "en"
        versionId = 2
        baseUrl = "https://hstream.moe"
    }

    deeplink {
        path("/hentai/..*")
    }
}
