plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeLatinoHD"
    className = "AnimeLatinoHD"
    versionCode = 56
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:streamwishextractor"))
}
