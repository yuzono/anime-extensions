plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "TioanimeH"
    versionCode = 39
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        name = "TioAnime"
        lang = "es"
        baseUrl = "https://tioanime.com"
    }
    source {
        name = "TioHentai"
        lang = "es"
        baseUrl = "https://tiohentai.com"
    }
}

dependencies {
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:universalextractor"))
}
