plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Oploverz"
    versionCode = 29
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "id"
        baseUrl = "https://oploverz.site"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:universalextractor"))
}
