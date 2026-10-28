plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Aniweek"
    versionCode = 19
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "ko"
        baseUrl = "https://aniweek.com"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
