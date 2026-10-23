plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "VerPelisTop"
    className = "VerPelisTop"
    versionCode = 10
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://www1.verpelis.top"
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:playlistutils"))
}
