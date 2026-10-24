plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "ANIMEWORLD.tv"
    versionCode = 61
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "it"
        baseUrl = "https://www.animeworld.ac"
    }
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:vidguardextractor"))
}
