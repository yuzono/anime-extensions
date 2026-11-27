import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeJara"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "es"
        baseUrl = "https://animejara.com"
    }
}

dependencies {
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:streamtapeextractor"))
}
