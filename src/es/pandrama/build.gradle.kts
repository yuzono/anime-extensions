plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Pandrama"
    className = "Pandrama"
    versionCode = 12
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:vkextractor"))
}
