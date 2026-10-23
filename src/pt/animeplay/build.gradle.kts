plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimePlay"
    className = "AnimePlay"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://animeplay.cloud"
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:playlistutils"))
}
