plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeCube"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://animecube.live"
    }
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:rumbleextractor"))
    implementation(project(":lib:youtubeextractor"))
}
