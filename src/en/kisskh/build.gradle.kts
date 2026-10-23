plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "KissKH"
    className = "KissKH"
    versionCode = 9
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
