plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimesOnlineCloud"
    className = "AnimesOnlineCloud"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://animesonline.cloud"
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:playlistutils"))
}
