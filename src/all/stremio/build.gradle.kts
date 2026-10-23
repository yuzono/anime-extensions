plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Stremio"
    className = "Stremio"
    versionCode = 13
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    torrent = true
}

dependencies {
    implementation("org.apache.commons:commons-text:1.15.0")
}
