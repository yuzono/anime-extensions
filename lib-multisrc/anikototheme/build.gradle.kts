import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 2

dependencies {
    implementation(project(":lib:m3u8server"))
    implementation(project(":lib:playlistutils"))
}
