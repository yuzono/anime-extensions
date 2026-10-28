plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "MonosChinos"
    versionCode = 57
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://monoschinos.st"
    }
}

dependencies {
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:luluextractor"))
}
