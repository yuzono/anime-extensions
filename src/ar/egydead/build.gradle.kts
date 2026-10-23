plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Egy Dead"
    className = "EgyDead"
    versionCode = 30
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:streamwishextractor"))
}
