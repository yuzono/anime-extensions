plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:vkextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:kwikextractor"))
    implementation(project(":lib:playlistutils"))
}
