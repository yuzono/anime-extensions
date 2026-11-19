plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HentaiTV"
    className = "HentaiTV"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:universalextractor"))
}
