plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AsiaLiveAction"
    className = "AsiaLiveAction"
    versionCode = 53
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vkextractor"))
    implementation(project(":lib:vidguardextractor"))
}
