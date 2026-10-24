plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AsyaAnimeleri"
    versionCode = 19
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"

    source {
        lang = "tr"
        baseUrl = "https://asyaanimeleri.top"
    }
}

dependencies {
    implementation(project(":lib:vkextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:gdriveplayerextractor"))
    implementation(project(":lib:doodextractor"))
}
