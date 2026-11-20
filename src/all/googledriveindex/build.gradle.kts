import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "GoogleDriveIndex"
    versionCode = 8
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "all"
        baseUrl = ""
    }
}
