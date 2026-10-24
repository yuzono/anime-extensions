plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeKai (Unoriginal)"
    versionCode = 0
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    theme = "anikototheme"

    source {
        lang = "en"
        baseUrl("https://animekaitv.to") {
            mirrors = listOf("https://anikaitv.to", "https://animekai.se", "https://anikai.se")
        }
    }
}
