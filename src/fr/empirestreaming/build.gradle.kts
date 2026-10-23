plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "EmpireStreaming"
    className = "EmpireStreaming"
    versionCode = 29
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:doodextractor"))
}
