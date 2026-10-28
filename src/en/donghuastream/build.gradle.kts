plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "DonghuaStream"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"

    source {
        lang = "en"
        baseUrl = "https://donghuastream.org"
    }
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:rumbleextractor"))
    implementation(project(":lib:streamplayextractor"))
}
