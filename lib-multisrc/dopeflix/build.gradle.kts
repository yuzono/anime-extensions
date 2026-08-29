import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 24

dependencies {
    api(project(":lib:dopeflixextractor"))
}
