plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Toonitalia"
    versionCode = 38
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "it"
        baseUrl = "https://toonitalia.green"
    }
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
}
