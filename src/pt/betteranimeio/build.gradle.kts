import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "BetterAnimeIo"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "pt-BR"
        baseUrl = "https://betteranime.io"
    }
}
