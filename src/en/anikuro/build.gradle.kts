plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniKuro"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl("https://anikuro.to") {
            mirrors = listOf("https://anikuro.ru")
        }
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
