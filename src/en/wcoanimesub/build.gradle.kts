import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "WcoAnimeSub"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "wcotheme"

    source {
        lang = "en"
        baseUrl = "https://www.wcoanimesub.tv"
    }
}
