import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeAv1"
    versionCode = 8
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "es"
        baseUrl = "https://animeav1.com"
    }
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:pixeldrainextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:megaextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:universalextractor"))
}
