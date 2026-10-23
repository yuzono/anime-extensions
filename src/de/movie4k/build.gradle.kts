plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Movie4k"
    className = "Movie4k"
    versionCode = 20
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:voeextractor"))
}
