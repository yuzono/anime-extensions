plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeMovil"
    versionCode = 50
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://animemeow.xyz"
    }
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:burstcloudextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:fastreamextractor"))
    implementation(project(":lib:upstreamextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:streamlareextractor"))
    implementation(project(":lib:universalextractor"))
}
