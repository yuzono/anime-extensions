plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeIto"
    versionCode = 10
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    theme = "animestream"

    source {
        name = "Animeito"
        lang = "pt-BR"
        baseUrl = "https://animesonline.io"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:m3u8server"))
}
