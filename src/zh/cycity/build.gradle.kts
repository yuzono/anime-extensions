plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Cycity"
    className = "Cycity"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:cryptoaes"))
}
