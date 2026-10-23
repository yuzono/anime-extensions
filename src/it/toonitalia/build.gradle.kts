plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Toonitalia"
    className = "Toonitalia"
    versionCode = 38
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
}
