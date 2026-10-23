plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "WIT ANIME"
    className = "WitAnime"
    versionCode = 65
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:vidbomextractor"))
}
