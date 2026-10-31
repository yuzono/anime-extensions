plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "BLZone"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://blzone.net"
    }
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:voeextractor"))
}
