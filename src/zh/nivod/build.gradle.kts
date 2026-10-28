plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Nivod"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "泥视频"
        lang = "zh"
        baseUrl = "https://www.nivod.cc"
    }
}
