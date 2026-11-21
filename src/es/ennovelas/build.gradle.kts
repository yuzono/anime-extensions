import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "EnNovelas (Dead)"
    versionCode = 29
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "EnNovelas"
        lang = "es"
        baseUrl = "https://l.ennovelas-tv.com"
    }
}
