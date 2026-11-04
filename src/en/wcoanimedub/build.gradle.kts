import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "WcoAnimeDub"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "wcotheme"

    source {
        lang = "en"
        baseUrl = "https://www.wcoanimedub.tv"
    }
}
