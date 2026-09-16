import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 7
dependencies {
    implementation(project(":lib:playlistutils"))
}
