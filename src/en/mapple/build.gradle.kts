plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Mapple"
    versionCode = 8
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://mapple.fun"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
