plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "LMAnime"
    className = "LMAnime"
    versionCode = 22
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"
    baseUrl = "https://lmanime.com"
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:streamwishextractor"))
}
