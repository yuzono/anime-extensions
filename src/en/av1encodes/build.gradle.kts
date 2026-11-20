import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AV1Encodes"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "en"
        baseUrl {
            mirrors(
                "av1encodes.com (default)" to "https://av1encodes.com",
                "animealpha.cc" to "https://animealpha.cc",
                "av1please.com (mirror)" to "https://av1please.com",
            )
        }
    }
}
