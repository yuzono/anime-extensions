plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "FanPelis"
    versionCode = 26
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://fanpelis.la"
    }
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:doodextractor"))
}
