plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "MhdFlix"
    versionCode = 25
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://ww1.mhdflix.com"
    }
}

dependencies {
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:luluextractor"))
    implementation(project(":lib:universalextractor"))
}
