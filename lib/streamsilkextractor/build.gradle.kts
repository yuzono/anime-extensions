plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":core"))
}
