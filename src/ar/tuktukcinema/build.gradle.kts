plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Tuktuk Cinema"
    className = "Tuktukcinema"
    versionCode = 9
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:megamaxmultiserver"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidbomextractor"))
    implementation(project(":lib:vidlandextractor"))
    implementation(project(":lib:i18n"))
}
