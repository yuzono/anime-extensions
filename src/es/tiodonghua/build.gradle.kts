plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Tiodonghua.com"
    versionCode = 19
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"

    source {
        lang = "es"
        baseUrl = "https://anime.tiodonghua.com"
    }
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:youruploadextractor"))
}
