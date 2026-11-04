plugins {
    alias(kei.plugins.multisrc)
}

dependencies {
    api(project(":lib:playlistutils"))
}

yuzono {
    baseVersionCode = 8
    libVersion = "14"
}
