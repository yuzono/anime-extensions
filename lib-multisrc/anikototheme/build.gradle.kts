plugins {
    alias(kei.plugins.multisrc)
}

dependencies {
    implementation(project(":lib:playlistutils"))
}

yuzono {
    baseVersionCode = 9
    libVersion = "16"
}
