import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animenosub"
    versionCode = 25
    contentWarning = ContentWarning.MIXED
    libVersion = "14"
    theme = "animestream"

    source {
        lang = "en"
        baseUrl = "https://animenosub.to"
    }
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidmolyextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
}
