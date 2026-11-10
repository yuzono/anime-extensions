import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animenix"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "es"
        baseUrl = "https://animenix.com"
    }
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:universalextractor"))
}
