plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "YummyAnime"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "ru"
        baseUrl = "https://ru.yummyani.me"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:vkextractor"))
}
