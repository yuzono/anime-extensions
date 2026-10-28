plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "VerAni.me"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://verani.me"
    }
}

dependencies {
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:pixeldrainextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:youruploadextractor"))
}
