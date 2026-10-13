import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 9
dependencies {
    implementation(project(":lib:playlistutils"))
}
