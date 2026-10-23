plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "OtakuFR"
    className = "OtakuFR"
    versionCode = 44
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
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
