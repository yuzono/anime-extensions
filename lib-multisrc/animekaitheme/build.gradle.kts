import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 4

dependencies {
    implementation(project(":lib:megaupextractor"))
}
