plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Samehadaku"
    className = "Samehadaku"
    versionCode = 10
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
