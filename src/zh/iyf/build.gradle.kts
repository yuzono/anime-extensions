import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "iyf"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "爱壹帆"
        lang = "zh"
        baseUrl = "https://www.iyf.tv"
    }
}
