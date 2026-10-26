plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Rule34Video"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://rule34video.com"
    }

    deeplink {
        path("/video/..*")
        path("/videos/..*")
    }
}
