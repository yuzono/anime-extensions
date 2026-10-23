plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "EstrenosDoramas"
    className = "EstrenosDoramas"
    versionCode = 21
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
}
