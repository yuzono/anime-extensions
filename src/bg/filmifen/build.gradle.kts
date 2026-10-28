plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "FilmiFen"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "bg"
        baseUrl = "https://filmifen.com"
    }
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:youtubeextractor"))
}
