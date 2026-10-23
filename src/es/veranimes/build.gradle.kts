plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "VerAnimes"
    className = "VerAnimes"
    versionCode = 26
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:universalextractor"))
}
