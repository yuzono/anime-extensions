import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hanime1.me"
    versionCode = 8
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "zh"
        baseUrl = "https://hanime1.me"
    }
}
