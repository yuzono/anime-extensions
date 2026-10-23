plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeQ"
    className = "AnimeQ"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://animeq.cloud"
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:playlistutils"))
}
