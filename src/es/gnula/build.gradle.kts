plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Gnula"
    versionCode = 52
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://gnula.life"
    }
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:streamlareextractor"))
    implementation(project(":lib:fastreamextractor"))
    implementation(project(":lib:upstreamextractor"))
    implementation(project(":lib:burstcloudextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:streamsilkextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:universalextractor"))
}
