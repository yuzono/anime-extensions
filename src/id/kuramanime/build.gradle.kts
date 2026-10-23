plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Kuramanime"
    className = "Kuramanime"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:vidguardextractor"))
}
