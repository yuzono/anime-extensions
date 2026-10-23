plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "FASELHD"
    className = "FASELHD"
    versionCode = 29
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:synchrony"))
}
