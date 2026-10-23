plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Aniweek"
    className = "Aniweek"
    versionCode = 19
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
