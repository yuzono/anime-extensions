plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hentaila"
    className = "Hentaila"
    versionCode = 49
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
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
