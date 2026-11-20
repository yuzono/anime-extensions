import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeSogo"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "16"
    theme = "anikototheme"

    source {
        lang = "en"
        baseUrl = "https://animesogo.to"
    }
}
