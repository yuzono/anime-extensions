plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeWorld India"
    className = "AnimeWorldIndiaFactory"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
