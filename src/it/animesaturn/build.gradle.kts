plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime Saturn"
    versionCode = 15
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        name = "AnimeSaturn"
        lang = "it"
        baseUrl {
            mirrors(
                "https://animesaturn.net",
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
