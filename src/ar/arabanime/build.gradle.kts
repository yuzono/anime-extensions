import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "ArabAnime"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "ar"
        baseUrl = "https://www.arabanime.net"
    }
}
