plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "LMAnime"
    versionCode = 22
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"

    source {
        lang = "all"
        baseUrl = "https://lmanime.com"
    }
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:streamwishextractor"))
}
