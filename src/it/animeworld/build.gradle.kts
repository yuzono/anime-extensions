plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "ANIMEWORLD.tv"
    className = "ANIMEWORLD"
    versionCode = 61
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:vidguardextractor"))
}
