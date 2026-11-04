import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "UHD Movies"
    versionCode = 19
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://uhdmovies.vip"
    }
}
