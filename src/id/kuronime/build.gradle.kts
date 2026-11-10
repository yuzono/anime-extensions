import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Kuronime"
    versionCode = 14
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "id"
        baseUrl = "https://tv1.kuronime.vip"
    }
}

dependencies {
    implementation(project(":lib:streamlareextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:unpacker"))
}
