plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Jkhentai"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://www.jkhentai.net"
    }
}

dependencies {
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:streamtapeextractor"))
}
