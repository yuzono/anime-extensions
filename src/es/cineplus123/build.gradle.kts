plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Cineplus123"
    className = "Cineplus123"
    versionCode = 20
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://cineplus123.org"
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:universalextractor"))
}
