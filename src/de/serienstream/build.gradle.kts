import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Serienstream"
    versionCode = 36
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "de"
        baseUrl = "https://serienstream.to"
    }
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
}
