plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "BLZone"
    className = "BLZone"
    versionCode = 10
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:voeextractor"))
}
