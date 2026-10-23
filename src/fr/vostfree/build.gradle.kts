plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Vostfree"
    className = "Vostfree"
    versionCode = 43
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:vudeoextractor"))
}
