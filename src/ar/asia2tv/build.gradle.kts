plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "asia2tv"
    className = "Asia2TV"
    versionCode = 39
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:vidbomextractor"))
}
