plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniKuro"
    className = "AniKuro"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
