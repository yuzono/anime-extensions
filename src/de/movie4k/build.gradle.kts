plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Movie4k"
    versionCode = 20
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "de"
        baseUrl = "https://movie4k.stream"
    }
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:voeextractor"))
}
