plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Wiflix"
    className = "Wiflix"
    versionCode = 30
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "datalifeengine"
    baseUrl = "https://flemmix.best"
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:vidoextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:streamdavextractor"))
    implementation(project(":lib:vudeoextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:upstreamextractor"))
    implementation(project(":lib:voeextractor"))
}
