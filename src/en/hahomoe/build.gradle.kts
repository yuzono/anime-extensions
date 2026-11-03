import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "haho.moe"
    versionCode = 10
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://haho.moe"
    }
}
