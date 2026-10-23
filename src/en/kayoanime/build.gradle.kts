plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Kayoanime"
    className = "Kayoanime"
    versionCode = 13
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:googledriveextractor"))
}
