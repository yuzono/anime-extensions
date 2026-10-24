plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hackstore"
    versionCode = 43
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://www.hackstore.fo"
    }
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:goodstreamextractor"))
    implementation(project(":lib:universalextractor"))
}
