plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlistutils"))
}
