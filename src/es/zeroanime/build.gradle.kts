plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "zeroanime"
    className = "Zeroanime"
    versionCode = 15
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:mp4uploadextractor"))
}
