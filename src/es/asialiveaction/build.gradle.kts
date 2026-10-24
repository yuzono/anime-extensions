plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AsiaLiveAction"
    versionCode = 53
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://asialiveaction.com"
    }
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vkextractor"))
    implementation(project(":lib:vidguardextractor"))
}
