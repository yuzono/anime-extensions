plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Rule34Video"
    versionCode = 10
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://rule34video.com"
    }

    deeplink {
        host("rule34video.com")
        path("/videos/..*")
    }
}
