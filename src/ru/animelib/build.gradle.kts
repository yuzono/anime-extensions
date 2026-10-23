plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animelib"
    className = "Animelib"
    versionCode = 17
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
