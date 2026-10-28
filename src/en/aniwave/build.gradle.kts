plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniWave (Unoriginal)"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    theme = "anikototheme"

    source {
        lang = "en"
        baseUrl("https://animewave.to") {
            mirrors = listOf("https://aniwave.cz")
        }
    }
}
