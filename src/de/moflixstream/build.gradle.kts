plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Moflix-Stream"
    className = "MoflixStream"
    versionCode = 30
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
}
