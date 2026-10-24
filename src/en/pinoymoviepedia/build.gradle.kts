plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "PinoyMoviePedia"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "en"
        baseUrl = "https://pinoymoviepedia.ru"
    }
}

dependencies {
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:doodextractor"))
}
