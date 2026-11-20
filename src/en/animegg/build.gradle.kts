import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeGG"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://www.animegg.org"
    }
}
