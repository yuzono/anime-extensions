plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "WIT ANIME"
    versionCode = 66
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "ar"
        baseUrl("https://witanime.site") {
            mirrors = listOf(
                "https://witanime.cyou",
                "https://witanime.pics",
                "https://witanime.com",
            )
        }
    }
}

dependencies {
    implementation(project(":lib:cloudflareinterceptor"))
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:universalextractor"))
}
