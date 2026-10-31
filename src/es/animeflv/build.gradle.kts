plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeFLV"
    versionCode = 78
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://www3.animeflv.net"
    }
}

dependencies {
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:universalextractor"))
}
