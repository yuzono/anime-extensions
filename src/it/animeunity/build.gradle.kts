import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeUnity"
    versionCode = 11
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "it"
        baseUrl {
            custom("https://animeunity.cx")
        }
    }
}
