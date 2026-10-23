plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Mapple"
    className = "Mapple"
    versionCode = 7
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
