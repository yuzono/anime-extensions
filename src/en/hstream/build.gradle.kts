plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hstream"
    className = "Hstream"
    versionCode = 12
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    deeplink {
        host("hstream.moe")
        path("/hentai/..*")
    }
}
