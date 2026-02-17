plugins {
    id("lib-android")
}

dependencies {
    implementation(libs.jsunpacker)
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:synchrony"))
}
