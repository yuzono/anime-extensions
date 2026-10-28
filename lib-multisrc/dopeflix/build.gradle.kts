plugins {
    alias(kei.plugins.multisrc)
}

dependencies {
    api(project(":lib:dopeflixextractor"))
}

yuzono {
    baseVersionCode = 27
    libVersion = "14"
}
