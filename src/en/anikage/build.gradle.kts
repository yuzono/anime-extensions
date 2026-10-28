plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anikage"
    versionCode = 9
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://anikage.cc"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
