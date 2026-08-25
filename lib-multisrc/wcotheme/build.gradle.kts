import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

dependencies {
    api(project(":lib:playlistutils"))
}

baseVersionCode = 6
