plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "BeatZ Anime"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://www.beatz-anime.net"
    }
}
