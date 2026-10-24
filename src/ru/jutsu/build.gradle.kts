plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Jutsu TV"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "ru"
        baseUrl = "https://jutsu.tv"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
