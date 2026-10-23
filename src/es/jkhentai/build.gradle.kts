plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Jkhentai"
    className = "Jkhentai"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:streamtapeextractor"))
}
