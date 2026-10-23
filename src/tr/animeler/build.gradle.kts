plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animeler"
    className = "Animeler"
    versionCode = 36
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    deeplink {
        host("animeler.me")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:gdriveplayerextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:streamlareextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:vudeoextractor"))
}
