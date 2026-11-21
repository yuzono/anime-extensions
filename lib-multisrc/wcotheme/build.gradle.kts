plugins {
    alias(kei.plugins.multisrc)
}

dependencies {
    api(project(":lib:playlistutils"))
}

yuzono {
    baseVersionCode = 9
    libVersion = "14"
}
