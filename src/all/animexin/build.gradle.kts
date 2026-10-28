plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeXin"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"

    source {
        lang = "all"
        id = 4620219025406449669L
        baseUrl = "https://animexin.dev"
    }
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:gdriveplayerextractor"))
    implementation(project(":lib:doodextractor"))
}
