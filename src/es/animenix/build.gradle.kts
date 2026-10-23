plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animenix"
    className = "Animenix"
    versionCode = 23
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://animenix.com"
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:universalextractor"))
}
