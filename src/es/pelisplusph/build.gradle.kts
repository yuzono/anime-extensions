plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "PelisPlusPh"
    versionCode = 82
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "pelisplus"

    source {
        lang = "es"
        baseUrl = "https://www.pelisplushd.la"
    }
}
