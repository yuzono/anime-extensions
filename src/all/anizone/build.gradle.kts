plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniZone"
    className = "AniZone"
    versionCode = 15
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
