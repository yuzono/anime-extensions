plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Xiaoxintv"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "小宝影院"
        lang = "zh"
        baseUrl = "https://xiaoxintv.cc"
    }
}
