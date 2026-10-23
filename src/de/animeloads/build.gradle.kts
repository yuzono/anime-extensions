plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime-Loads"
    className = "AnimeLoads"
    versionCode = 27
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
}
