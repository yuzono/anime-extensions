plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hentai Saturn"
    versionCode = 15
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        name = "HentaiSaturn"
        lang = "it"
        baseUrl("https://hentaisaturn.tv") {
            mirrors = listOf(
                "https://hentaisaturn.com",
            )
        }
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
