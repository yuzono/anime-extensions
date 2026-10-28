plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniWorld"
    versionCode = 41
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "de"
        id = 8286900189409315836L
        baseUrl = "https://aniworld.to"
    }
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:vidmolyextractor"))
}
