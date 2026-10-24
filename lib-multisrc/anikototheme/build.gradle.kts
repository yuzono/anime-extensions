plugins {
    alias(kei.plugins.multisrc)
}

dependencies {
    implementation(project(":lib:playlistutils"))
}

yuzono {
    baseVersionCode = 10
    libVersion = "16"
}
