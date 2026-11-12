import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "YoMovies"
    versionCode = 37
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "hi"
        baseUrl = "https://yomovies.academy"
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:playlistutils"))
}
