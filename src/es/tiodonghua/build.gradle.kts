plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Tiodonghua.com"
    className = "Tiodonghua"
    versionCode = 19
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"
    baseUrl = "https://anime.tiodonghua.com"
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:youruploadextractor"))
}
