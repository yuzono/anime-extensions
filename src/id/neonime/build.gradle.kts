plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "NeoNime"
    versionCode = 27
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "id"
        baseUrl = "https://neonime.ink"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:gdriveplayerextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:okruextractor"))
}
