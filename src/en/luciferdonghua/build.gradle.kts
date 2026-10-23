plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "LuciferDonghua"
    className = "LuciferDonghua"
    versionCode = 20
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"
    baseUrl = "https://luciferdonghua.in"
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:rumbleextractor"))
}
