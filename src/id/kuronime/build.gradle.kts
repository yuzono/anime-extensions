plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Kuronime"
    className = "Kuronime"
    versionCode = 13
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:streamlareextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:unpacker"))
}
