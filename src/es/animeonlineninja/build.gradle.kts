plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeOnline.Ninja"
    versionCode = 63
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "es"
        baseUrl = "https://ww3.animeonline.ninja"
    }
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:mp4uploadextractor"))
}
