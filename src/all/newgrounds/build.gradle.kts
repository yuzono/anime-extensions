import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Newgrounds"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "all"
        baseUrl = "https://www.newgrounds.com"
    }
}
