plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Kayoanime"
    versionCode = 13
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "en"
        id = 203922289858257167L
        baseUrl = "https://kayoanime.com"
    }
}

dependencies {
    implementation(project(":lib:googledriveextractor"))
}
