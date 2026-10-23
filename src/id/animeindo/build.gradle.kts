plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeIndo"
    className = "AnimeIndo"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"
    baseUrl = "https://animeindo.skin"
}

dependencies {
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:gdriveplayerextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:okruextractor"))
}
