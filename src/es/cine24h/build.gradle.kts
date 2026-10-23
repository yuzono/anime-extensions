plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Cine24h"
    className = "Cine24h"
    versionCode = 26
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
}

dependencies {
    implementation(project(":lib:fastreamextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:vidguardextractor"))
    implementation(project(":lib:universalextractor"))
}
