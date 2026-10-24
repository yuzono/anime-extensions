plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime-Loads"
    versionCode = 27
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "de"
        id = 655155856096L
        baseUrl = "https://www.anime-loads.org"
    }
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
}
