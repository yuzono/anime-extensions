plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "zeroanime"
    versionCode = 15
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://www4.zeroanime.xyz"
    }
}

dependencies {
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:mp4uploadextractor"))
}
