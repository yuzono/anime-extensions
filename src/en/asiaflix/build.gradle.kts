import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AsiaFlix"
    versionCode = 34
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
