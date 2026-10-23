plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Jellyfin"
    className = "JellyfinFactory"
    versionCode = 32
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:hlsdash"))
    implementation("org.apache.commons:commons-text:1.15.0")
}
