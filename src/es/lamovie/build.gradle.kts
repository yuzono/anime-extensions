plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "LaMovie"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dopeflix"

    source {
        lang = "es"
        id = 5419283741928374105L
        baseUrl = "https://la.movie"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:pixeldrainextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:goodstreamextractor"))
    implementation(project(":lib:unpacker"))
}
