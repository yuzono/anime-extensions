plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime Saturn"
    className = "AnimeSaturn"
    versionCode = 14
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
