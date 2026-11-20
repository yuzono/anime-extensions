import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniWave (Unoriginal)"
    versionCode = 4
    contentWarning = ContentWarning.MIXED
    libVersion = "16"
    theme = "anikototheme"

    source {
        lang = "en"
        baseUrl {
            mirrors(
                "https://animewave.to",
                "https://aniwave.cz",
            )
        }
    }
}
