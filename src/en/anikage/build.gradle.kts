plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anikage"
    className = "Anikage"
    versionCode = 8
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
