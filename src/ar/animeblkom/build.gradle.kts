plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime Blkom"
    className = "AnimeBlkom"
    versionCode = 29
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:okruextractor"))
}
