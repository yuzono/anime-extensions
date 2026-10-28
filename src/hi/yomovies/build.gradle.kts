plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "YoMovies"
    versionCode = 35
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "hi"
        baseUrl = "https://yomovies.town"
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:playlistutils"))
}
