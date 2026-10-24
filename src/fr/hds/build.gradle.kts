plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HDS"
    versionCode = 17
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "fr"
        baseUrl = "https://on1.hds.quest"
    }
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:vidhideextractor"))
}
