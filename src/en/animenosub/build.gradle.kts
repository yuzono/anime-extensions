import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animenosub"
    versionCode = 24
    contentWarning = ContentWarning.NSFW
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
