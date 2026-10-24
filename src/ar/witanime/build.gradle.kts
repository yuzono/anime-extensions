plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "WIT ANIME"
    versionCode = 65
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "ar"
        baseUrl = "https://witanime.cyou"
    }
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:vidbomextractor"))
}
