plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "TRAnimeCI"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"

    source {
        lang = "tr"
        baseUrl = "https://tranimaci.com"
    }
}
