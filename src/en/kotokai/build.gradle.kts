import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeKai (Unoriginal)"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "16"
    theme = "anikototheme"

    source {
        lang = "en"
        baseUrl {
            mirrors(
                "https://animekaitv.to",
                "https://anikaitv.to",
                "https://animekai.se",
                "https://anikai.se",
            )
        }
    }
}
