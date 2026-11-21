import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Wiflix"
    versionCode = 32
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "datalifeengine"

    source {
        lang = "fr"
        baseUrl = "https://flemmix.run"
    }
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
