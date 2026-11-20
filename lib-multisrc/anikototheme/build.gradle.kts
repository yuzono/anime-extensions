plugins {
    alias(kei.plugins.multisrc)
}

dependencies {
    implementation(project(":lib:playlistutils"))
}

yuzono {
    baseVersionCode = 11
    libVersion = "16"
}
