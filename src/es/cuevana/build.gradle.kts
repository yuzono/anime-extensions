plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Cuevana"
    className = "CuevanaFactory"
    versionCode = 68
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:universalextractor"))
}
