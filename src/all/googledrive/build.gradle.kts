import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Google Drive"
    versionCode = 19
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "all"
        id = 4222017068256633289L
        baseUrl = "https://drive.google.com"
    }
}

dependencies {
    implementation(project(":lib:googledriveextractor"))
}
