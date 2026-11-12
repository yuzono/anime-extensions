import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeToki"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://animetoki.com"
    }
}
