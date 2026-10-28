plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "GoogleDriveIndex"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "all"
        baseUrl = ""
        skipCodeGen = true
    }
}
