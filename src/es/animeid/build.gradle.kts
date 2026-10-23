plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeID"
    className = "AnimeID"
    versionCode = 28
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:universalextractor"))
}
