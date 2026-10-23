plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "FilmPalast"
    className = "FilmPalast"
    versionCode = 28
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:voeextractor"))
}
