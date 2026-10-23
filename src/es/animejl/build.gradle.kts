plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animejl"
    className = "Animejl"
    versionCode = 23
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:mp4uploadextractor"))
}
