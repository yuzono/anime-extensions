plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeWorld India"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    listOf("all", "bn", "en", "hi", "ja", "ml", "mr", "ta", "te").forEach {
        source {
            lang = it
            baseUrl = "https://anime-world.co"
        }
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
