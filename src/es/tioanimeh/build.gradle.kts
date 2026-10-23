plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "TioanimeH"
    className = "TioanimeHFactory"
    versionCode = 39
    contentWarning = ContentWarning.MIXED
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:universalextractor"))
}
