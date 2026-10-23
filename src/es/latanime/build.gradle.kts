plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Latanime"
    className = "Latanime"
    versionCode = 44
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:universalextractor"))
}
