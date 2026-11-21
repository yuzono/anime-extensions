import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "TRAnimeCI"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"

    source {
        lang = "tr"
        baseUrl = "https://tranimaci.com"
    }
}
