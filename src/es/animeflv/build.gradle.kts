plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeFLV"
    className = "AnimeFlv"
    versionCode = 78
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:universalextractor"))
}
