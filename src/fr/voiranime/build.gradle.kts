plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Voiranime"
    className = "Voiranime"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:universalextractor"))
}
