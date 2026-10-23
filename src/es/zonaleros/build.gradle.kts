plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Zonaleros"
    className = "Zonaleros"
    versionCode = 20
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:vidhideextractor"))
}
