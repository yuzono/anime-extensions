import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "LACartoons"
    versionCode = 29
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://www.lacartoons.com"
    }
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
