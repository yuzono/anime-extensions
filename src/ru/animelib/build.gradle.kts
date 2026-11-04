import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animelib"
    versionCode = 18
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "ru"
        baseUrl {
            mirrors(
                "https://animelib.org/ru",
                "https://v3.animelib.org/ru",
                "https://v4.animelib.org/ru",
                "https://v5.animelib.org/ru",
            )
        }
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
