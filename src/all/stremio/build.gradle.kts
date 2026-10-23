plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Stremio"
    className = "Stremio"
    versionCode = 11
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
}

dependencies {
    implementation("org.apache.commons:commons-text:1.15.0")
}
