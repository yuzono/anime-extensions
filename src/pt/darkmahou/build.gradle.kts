plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "DarkMahou"
    className = "DarkMahou"
    versionCode = 14
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"
    baseUrl = "https://darkmahou.org"
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlistutils"))
}
