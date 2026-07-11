import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeToki"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://animetoki.com"
    }
}
