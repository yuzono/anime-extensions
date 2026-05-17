plugins {
    id("lib-multisrc")
}

baseVersionCode = 1

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
}
