plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime Saturn"
    versionCode = 15
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "AnimeSaturn"
        lang = "it"
        baseUrl("https://animesaturn.net") {
            mirrors = listOf(
                "https://animesaturn.cx",
                "https://animesaturn.cc",
                "https://animesaturn.com",
                "https://animemars.org",
            )
        }
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
