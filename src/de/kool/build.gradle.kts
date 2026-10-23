plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Kool"
    className = "Kool"
    versionCode = 27
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:voeextractor"))
}
