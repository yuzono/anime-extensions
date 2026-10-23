plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Jkanime"
    className = "Jkanime"
    versionCode = 53
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:universalextractor"))
}
