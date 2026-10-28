plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hentaila"
    versionCode = 49
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://hentaila.com"
    }
}

dependencies {
    implementation(project(":lib:burstcloudextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:vidhideextractor"))
}
