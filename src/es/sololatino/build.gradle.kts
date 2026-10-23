plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "SoloLatino"
    className = "SoloLatino"
    versionCode = 25
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"
    baseUrl = "https://sololatino.net"
}

dependencies {
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:cryptoaes"))
}
