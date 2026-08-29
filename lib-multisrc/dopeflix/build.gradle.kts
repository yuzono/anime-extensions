import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 25

dependencies {
    api(project(":lib:dopeflixextractor"))
}
