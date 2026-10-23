plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animes Online CC"
    className = "AnimesOnlineCC"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://animesonlinecc.to"
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
