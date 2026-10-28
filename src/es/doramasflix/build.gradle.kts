plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Doramasflix"
    versionCode = 54
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://doramasflix.in"
    }
}

dependencies {
    implementation(project(":lib:vudeoextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamlareextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:burstcloudextractor"))
    implementation(project(":lib:fastreamextractor"))
    implementation(project(":lib:upstreamextractor"))
    implementation(project(":lib:vidhideextractor"))
}
