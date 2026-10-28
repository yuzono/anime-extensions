plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeToast"
    versionCode = 36
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "de"
        baseUrl = "https://www.animetoast.cc"
    }
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:mp4uploadextractor"))
}
