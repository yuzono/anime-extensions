plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "OtakusTV"
    className = "OtakusTV"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:luluextractor"))
    implementation(project(":lib:universalextractor"))
}
