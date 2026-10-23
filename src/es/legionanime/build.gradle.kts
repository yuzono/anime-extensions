plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "LegionAnime"
    className = "LegionAnime"
    versionCode = 52
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:streamwishextractor"))
}
