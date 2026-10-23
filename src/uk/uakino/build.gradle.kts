plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "UAKino"
    className = "UAKino"
    versionCode = 6
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
