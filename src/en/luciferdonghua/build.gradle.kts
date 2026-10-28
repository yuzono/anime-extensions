plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "LuciferDonghua"
    versionCode = 20
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"

    source {
        lang = "en"
        baseUrl = "https://luciferdonghua.in"
    }
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:rumbleextractor"))
}
