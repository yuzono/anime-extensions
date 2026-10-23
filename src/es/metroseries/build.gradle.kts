plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "MetroSeries"
    className = "MetroSeries"
    versionCode = 34
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:burstcloudextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:fastreamextractor"))
    implementation(project(":lib:upstreamextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:universalextractor"))
}
