import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Voiranime"
    versionCode = 6
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "fr"
        baseUrl = "https://voiranime.cx/"
    }
}

dependencies {
    implementation(project(":lib:universalextractor"))
}
