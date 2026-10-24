plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AV1Encodes"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "en"
        baseUrl("https://av1encodes.com") {
            mirrors = listOf("https://animealpha.cc", "https://av1please.com")
        }
    }
}
