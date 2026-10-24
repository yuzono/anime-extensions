plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "JetAnime"
    versionCode = 20
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "fr"
        baseUrl = "https://ssl.jetanimes.com"
    }
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlistutils"))
}
