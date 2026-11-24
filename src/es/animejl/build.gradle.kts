import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animejl"
    versionCode = 25
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "es"
        baseUrl = "https://www.anime-jl.net"
    }
}

dependencies {
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:burstcloudextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:mp4uploadextractor"))
}
