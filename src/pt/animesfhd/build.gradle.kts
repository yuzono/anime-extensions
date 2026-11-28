import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimesFHD"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        name = "AnimesFHD"
        lang = "pt-BR"
        baseUrl = "https://animesfhd.net"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
