plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HexaWatch"
    className = "HexaWatch"
    versionCode = 8
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
