plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeBum"
    className = "AnimeBum"
    versionCode = 22
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:gdriveplayerextractor"))
}
