plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "MundoDonghua"
    className = "MundoDonghua"
    versionCode = 47
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
}
