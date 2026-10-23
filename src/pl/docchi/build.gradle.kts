plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Docchi"
    className = "Docchi"
    versionCode = 17
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:vkextractor"))
    implementation(project(":lib:googledriveextractor"))
    implementation(project(":lib:cdaextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:lycorisextractor"))
    implementation(project(":lib:luluextractor"))
    implementation(project(":lib:streamupextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:megaextractor"))
}
