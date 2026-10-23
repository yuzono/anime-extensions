plugins {
    alias(kei.plugins.multisrc)
}

dependencies {
    api(project(":lib:dopeflixextractor"))
}

yuzono {
    baseVersionCode = 26
    libVersion = "14"
}
