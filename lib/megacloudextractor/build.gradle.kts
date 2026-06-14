plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(project(":lib:m3u8server"))
    implementation(project(":lib:playlistutils"))
}
