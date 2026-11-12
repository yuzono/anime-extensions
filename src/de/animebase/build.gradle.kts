import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime-Base"
    versionCode = 49
    contentWarning = ContentWarning.MIXED
    libVersion = "14"

    source {
        lang = "de"
        baseUrl = "https://anime-base.net"
    }
}

dependencies {
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
}
