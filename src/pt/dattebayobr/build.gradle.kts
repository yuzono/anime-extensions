import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "DattebayoBR"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "Dattebayo BR"
        lang = "pt-BR"
        baseUrl = "https://www.dattebayo-br.com"
    }
}
