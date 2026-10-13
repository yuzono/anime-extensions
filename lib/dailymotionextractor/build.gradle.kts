plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(project(":lib:hlsdash"))
    implementation(project(":lib:playlistutils"))
}
