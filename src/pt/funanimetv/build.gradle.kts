import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Fun Anime TV"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "pt-BR"
        baseUrl = "https://betterclass.click"
    }
}
