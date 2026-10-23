plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "LACartoons"
    className = "Lacartoons"
    versionCode = 28
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:universalextractor"))
}
