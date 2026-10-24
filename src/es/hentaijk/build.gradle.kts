plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hentaijk"
    versionCode = 21
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://hentaijk.com"
    }
}

dependencies {
    implementation(project(":lib:okruextractor"))
}
