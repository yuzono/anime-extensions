import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Xiaoxintv"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "小宝影院"
        lang = "zh"
        baseUrl = "https://ibaotv.com"
    }
}
