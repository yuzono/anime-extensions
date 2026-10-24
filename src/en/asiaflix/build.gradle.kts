plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AsiaFlix"
    versionCode = 33
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://asiaflix.net"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:vidmolyextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:mixdropextractor"))
}
