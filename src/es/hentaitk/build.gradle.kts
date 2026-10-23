plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HentaiTk"
    className = "Hentaitk"
    versionCode = 30
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:universalextractor"))
}
