plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Kuramanime"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "id"
        baseUrl = "https://v8.kuramanime.tel"
    }
}

dependencies {
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:vidguardextractor"))
}
