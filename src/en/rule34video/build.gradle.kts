plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Rule34Video"
    className = "Rule34Video"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    deeplink {
        host("rule34video.com")
        path("/video/..*")
        path("/videos/..*")
    }
}
