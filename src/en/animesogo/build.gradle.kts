import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeSogo"
    versionCode = 0
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    theme = "anikototheme"

    source {
        lang = "en"
        baseUrl = "https://animesogo.to"
    }
}
