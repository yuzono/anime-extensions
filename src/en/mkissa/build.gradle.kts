import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "MKissa"
    versionCode = 75
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        id = 4709139914729853090L
        baseUrl = "https://mkissa.to/anime"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:streamlareextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:gogostreamextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamwishextractor"))
}
