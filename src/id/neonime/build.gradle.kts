plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "NeoNime"
    className = "NeoNime"
    versionCode = 27
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:gdriveplayerextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:okruextractor"))
}
