plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "FilmiFen"
    className = "FilmiFen"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:youtubeextractor"))
}
