plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimesDrive"
    className = "AnimesDrive"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://animesdrive.online"
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:playlistutils"))
}
