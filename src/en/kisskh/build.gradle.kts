plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "KissKH"
    versionCode = 10
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl("https://kisskh.ovh") {
            mirrors = listOf(
                "https://kisskh.do",
                "https://kisskh.co",
                "https://kisskh.id",
                "https://kisskh.la",
                "https://kisskh.is",
            )
        }
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
