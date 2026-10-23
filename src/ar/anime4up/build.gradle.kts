plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime4up"
    className = "Anime4Up"
    versionCode = 81
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:gdriveplayerextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:vidbomextractor"))
    implementation(project(":lib:voeextractor"))
}
