import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "OtakuFR"
    versionCode = 45
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "fr"
        baseUrl = "https://otakufr.cc"
    }
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:upstreamextractor"))
    implementation(project(":lib:unpacker"))
}
