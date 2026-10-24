plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniList"
    versionCode = 15
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://anilist.co"
    }
}
