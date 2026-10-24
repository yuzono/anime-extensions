plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Kinoking"
    versionCode = 33
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "de"
        baseUrl = "https://kinoking.cc"
    }
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:doodextractor"))
}
