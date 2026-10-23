plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Arab Seed"
    className = "ArabSeed"
    versionCode = 32
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamwishextractor"))
}
