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
        host("rule34video.com")
        path("/video/..*")
        path("/videos/..*")
    }
}
