plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "YummyAnime"
    className = "YummyAnime"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:vkextractor"))
}
