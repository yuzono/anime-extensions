import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Xvideos"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "all"
        baseUrl = "https://www.xvideos.com"
    }
}
