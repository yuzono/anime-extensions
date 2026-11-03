import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animeiat"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "ar"
        baseUrl = "https://api.animeiat.co/v1"
    }
}
