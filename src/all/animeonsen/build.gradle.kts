import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeOnsen"
    versionCode = 12
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "all"
        baseUrl = "https://www.animeonsen.xyz"
    }
}
