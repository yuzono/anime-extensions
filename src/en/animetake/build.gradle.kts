plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeTake"
    versionCode = 21
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://animetake.tv"
    }
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamtapeextractor"))
}
