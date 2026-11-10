import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Subsplease"
    versionCode = 9
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    torrent = true

    source {
        lang = "all"
        baseUrl = "https://subsplease.org"
    }
}
