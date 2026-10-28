plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Doramasyt"
    versionCode = 41
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://www.doramasyt.com"
    }
}

dependencies {
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:universalextractor"))
}
